package app.revanced.patches.music.video.information

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstruction
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.getInstruction
import app.revanced.patcher.extensions.or
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.revanced.patches.music.utils.fingerprints.SeekBarConstructorFingerprint
import app.revanced.patches.music.utils.integrations.Constants.VIDEO_PATH
import app.revanced.patches.music.utils.resourceid.SharedResourceIdPatch
import app.revanced.patches.music.video.information.fingerprints.BackgroundPlaybackVideoIdFingerprint
import app.revanced.patches.music.video.information.fingerprints.BackgroundPlaybackVideoIdParentFingerprint
import app.revanced.patches.music.video.information.fingerprints.PlayerControllerSetTimeReferenceFingerprint
import app.revanced.patches.music.video.information.fingerprints.VideoEndFingerprint
import app.revanced.patches.music.video.information.fingerprints.VideoIdParentFingerprint
import app.revanced.patches.music.video.information.fingerprints.VideoLengthFingerprint
import app.revanced.util.exception
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.util.MethodUtil

@Patch(dependencies = [SharedResourceIdPatch::class])
object VideoInformationPatch : BytecodePatch(
    setOf(
        BackgroundPlaybackVideoIdParentFingerprint,
        PlayerControllerSetTimeReferenceFingerprint,
        SeekBarConstructorFingerprint,
        VideoEndFingerprint,
        VideoIdParentFingerprint
    )
) {
    private const val INTEGRATIONS_CLASS_DESCRIPTOR =
        "$VIDEO_PATH/VideoInformation;"

    private var backgroundPlaybackInsertIndex = 0
    private var offset = 0
    private var playerInitInsertIndex = 4
    private var timeInitInsertIndex = 2
    private var videoIdIndex = 0

    private var backgroundPlaybackVideoIdRegister = 0
    private var videoIdRegister: Int = 0

    private lateinit var backgroundPlaybackMethod: MutableMethod
    private lateinit var videoIdMethod: MutableMethod
    private lateinit var playerInitMethod: MutableMethod
    private lateinit var timeMethod: MutableMethod

    lateinit var rectangleFieldName: String

    internal fun injectBackgroundPlaybackCall(
        methodDescriptor: String
    ) {
        backgroundPlaybackMethod.addInstructions(
            backgroundPlaybackInsertIndex, // move-result-object offset
            "invoke-static {v$backgroundPlaybackVideoIdRegister}, $methodDescriptor"
        )
    }

    /**
     * Adds an invoke-static instruction, called with the new id when the video changes
     * @param methodDescriptor which method to call. Params have to be `Ljava/lang/String;`
     */
    internal fun injectCall(
        methodDescriptor: String
    ) {
        videoIdMethod.addInstructions(
            videoIdIndex + offset, // move-result-object offset
            "invoke-static {v$videoIdRegister}, $methodDescriptor"
        )
    }

    private fun MutableMethod.insert(insertIndex: Int, register: String, descriptor: String) =
        addInstruction(insertIndex, "invoke-static { $register }, $descriptor")

    private fun MutableMethod.insertTimeHook(insertIndex: Int, descriptor: String) =
        insert(insertIndex, "p1, p2", descriptor)

    /**
     * Hook the player controller.  Called when a video is opened or the current video is changed.
     *
     * Note: This hook is called very early and is called before the video id, video time, video length,
     * and many other data fields are set.
     *
     * @param targetMethodClass The descriptor for the class to invoke when the player controller is created.
     * @param targetMethodName The name of the static method to invoke when the player controller is created.
     */
    internal fun onCreateHook(targetMethodClass: String, targetMethodName: String) =
        playerInitMethod.insert(
            playerInitInsertIndex++,
            "v0",
            "$targetMethodClass->$targetMethodName(Ljava/lang/Object;)V"
        )

    /**
     * Hook the video time.
     * The hook is usually called once per second.
     *
     * @param targetMethodClass The descriptor for the static method to invoke when the player controller is created.
     * @param targetMethodName The name of the static method to invoke when the player controller is created.
     */
    internal fun videoTimeHook(targetMethodClass: String, targetMethodName: String) =
        timeMethod.insertTimeHook(
            timeInitInsertIndex++,
            "$targetMethodClass->$targetMethodName(J)V"
        )

    override fun execute(context: BytecodeContext) {
        VideoEndFingerprint.result?.let {
            playerInitMethod =
                it.mutableClass.methods.first { method -> MethodUtil.isConstructor(method) }

            // hook the player controller for use through integrations
            onCreateHook(INTEGRATIONS_CLASS_DESCRIPTOR, "initialize")

            it.mutableMethod.apply {
                val seekHelperMethod = ImmutableMethod(
                    definingClass,
                    "seekTo",
                    listOf(ImmutableMethodParameter("J", annotations, "time")),
                    "Z",
                    AccessFlags.PUBLIC or AccessFlags.FINAL,
                    annotations, null,
                    MutableMethodImplementation(4)
                ).toMutable()

                val seekSourceEnumType = parameterTypes[1].toString()

                seekHelperMethod.addInstructions(
                    0, """
                            sget-object v0, $seekSourceEnumType->a:$seekSourceEnumType
                            invoke-virtual {p0, p1, p2, v0}, ${definingClass}->${name}(J$seekSourceEnumType)Z
                            move-result p1
                            return p1
                            """
                )
                it.mutableClass.methods.add(seekHelperMethod)
            }
        } ?: throw VideoEndFingerprint.exception


        /**
         * Set current video length
         */
        SeekBarConstructorFingerprint.result?.classDef?.let { classDef ->
            VideoLengthFingerprint.also {
                it.resolve(
                    context,
                    classDef
                )
            }.result?.let {
                it.mutableMethod.apply {
                    val rectangleReference =
                        getInstruction<ReferenceInstruction>(implementation!!.instructions.count() - 3).reference
                    rectangleFieldName = (rectangleReference as FieldReference).name

                    val videoLengthRegisterIndex = it.scanResult.patternScanResult!!.startIndex + 1
                    val videoLengthRegister =
                        getInstruction<OneRegisterInstruction>(videoLengthRegisterIndex).registerA
                    val dummyRegisterForLong =
                        videoLengthRegister + 1 // required for long values since they are wide

                    addInstruction(
                        videoLengthRegisterIndex + 1,
                        "invoke-static {v$videoLengthRegister, v$dummyRegisterForLong}, $INTEGRATIONS_CLASS_DESCRIPTOR->setVideoLength(J)V"
                    )
                }
            } ?: throw VideoLengthFingerprint.exception
        } ?: throw SeekBarConstructorFingerprint.exception


        /**
         * Set the video time method
         */
        PlayerControllerSetTimeReferenceFingerprint.result?.let {
            timeMethod = context.toMethodWalker(it.method)
                .nextMethod(it.scanResult.patternScanResult!!.startIndex, true)
                .getMethod() as MutableMethod
        } ?: throw PlayerControllerSetTimeReferenceFingerprint.exception


        /**
         * Set current video time
         */
        videoTimeHook(INTEGRATIONS_CLASS_DESCRIPTOR, "setVideoTime")


        /**
         * Inject call for background playback video id
         */
        BackgroundPlaybackVideoIdParentFingerprint.result?.let { parentResult ->
            BackgroundPlaybackVideoIdFingerprint.also {
                it.resolve(
                    context,
                    parentResult.classDef
                )
            }.result?.let {
                it.mutableMethod.apply {
                    backgroundPlaybackMethod = this
                    backgroundPlaybackInsertIndex = it.scanResult.patternScanResult!!.endIndex
                    backgroundPlaybackVideoIdRegister =
                        getInstruction<OneRegisterInstruction>(backgroundPlaybackInsertIndex).registerA
                    backgroundPlaybackInsertIndex++
                }
            } ?: throw BackgroundPlaybackVideoIdFingerprint.exception
        } ?: throw BackgroundPlaybackVideoIdParentFingerprint.exception


        /**
         * Inject call for video id
         */
        VideoIdParentFingerprint.result?.let {
            it.mutableMethod.apply {
                val targetIndex = it.scanResult.patternScanResult!!.endIndex

                val targetReference = getInstruction<ReferenceInstruction>(targetIndex).reference
                val targetClass = (targetReference as FieldReference).type

                videoIdMethod = context
                    .findClass(targetClass)!!
                    .mutableClass.methods.first { method ->
                        method.name == "handleVideoStageEvent"
                    }
            }
        } ?: throw VideoIdParentFingerprint.exception

        videoIdMethod.apply {
            for (index in implementation!!.instructions.size - 1 downTo 0) {
                if (getInstruction(index).opcode != Opcode.INVOKE_INTERFACE) continue

                val targetReference = getInstruction<ReferenceInstruction>(index).reference

                if (!targetReference.toString().endsWith("Ljava/lang/String;")) continue

                videoIdIndex = index + 1
                videoIdRegister = getInstruction<OneRegisterInstruction>(videoIdIndex).registerA

                break
            }
            offset++ // offset so setVideoId is called before any injected call
        }


        /**
         * Set current video id
         */
        injectCall("$INTEGRATIONS_CLASS_DESCRIPTOR->setVideoId(Ljava/lang/String;)V")
    }
}