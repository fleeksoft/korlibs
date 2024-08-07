package korlibs.audio.core.impl

import korlibs.audio.core.*
import korlibs.audio.sound.*
import korlibs.ffi.*
import korlibs.io.core.*
import korlibs.io.lang.*
import korlibs.math.geom.*
import korlibs.memory.*
import korlibs.platform.*

// https://openal.org/documentation/OpenAL_Programmers_Guide.pdf
internal object OpenALAudioSystem : AudioSystem() {
    val available: Boolean by lazy {
        runCatching { AL.alcGetInteger(null, AL.ALC_MAJOR_VERSION) }.getOrNull() != null
    }

    override val devices: List<AudioDevice> by lazy {
        val strings = AL.alcGetStringAsPointer(null, AL.ALC_ALL_DEVICES_SPECIFIER).toStringzList().takeIf { it.isNotEmpty() }
            ?: listOf(AL.alcGetString(null, AL.ALC_DEVICE_SPECIFIER))
        strings.mapIndexed { index, s ->
            AudioDevice(s ?: "", isDefault = index == 0, id = index.toLong())
        }
    }

    fun FFIPointer?.toStringzList(): List<String> = buildList {
        if (this@toStringzList == null) return@buildList

        var ptr = this@toStringzList
        while (ptr.getS8() != 0.toByte()) {
            val len = ptr.strlen()
            add(ptr.getStringz())
            ptr += len + 1
        }
    }

    override fun createPlayer(device: AudioDevice): AudioPlayer = OpenALAudioPlayer(device)

    class OpenALAudioPlayer(override val device: AudioDevice) : AudioPlayer() {
        val dev = AL.alcOpenDevice(device.name.takeIf { it.isNotBlank() }).also {
            println("openal.dev=$it")
        }
        val context = dev?.let { AL.alcCreateContext(it, null).also {
            AL.alcMakeContextCurrent(it)
            ffiScoped {
                AL.alListenerf(AL.AL_GAIN, 1f)
                AL.alListener3f(AL.AL_POSITION, 0f, 0f, 1.0f)
                AL.alListener3f(AL.AL_VELOCITY, 0f, 0f, 0f)
                AL.alListenerfv(AL.AL_ORIENTATION, floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f))
            }
        } }

        override var listenerGain: Float = 1f
            set(value) { field = value; AL.alListenerf(AL.AL_GAIN, value) }

        override var listenerSpeed: Vector3F = Vector3F.ZERO
            set(value) { field = value; AL.alListener3f(AL.AL_VELOCITY, value.x, value.y, value.z) }
        override var listenerPosition: Vector3F = Vector3F.ZERO
            set(value) { field = value; AL.alListener3f(AL.AL_POSITION, value.x, value.y, value.z) }
        override var listenerOrientation: AudioOrientation = AudioOrientation()
            set(value) {  field = value; AL.alListenerfv(AL.AL_ORIENTATION, floatArrayOf(value.at.x, value.at.y, value.at.z, value.up.x, value.up.y, value.up.z))  }

        override fun createSource(): AudioSource = OpenALAudioSource(this)

        override fun close() {
            AL.alcCloseDevice(dev)
        }

        fun makeCurrent() {
            AL.alcMakeContextCurrent(context)
        }
    }

    // https://github.com/korlibs/korlibs4/blob/backup/korau/src/nativeMain/kotlin/korlibs/audio/sound/backends/OpenAL.kt
    class OpenALAudioSource(override val player: OpenALAudioPlayer) : AudioSource() {
        val buffer = AL.alGenBuffer()
        val source = AL.alGenSource()

        override var pitch: Float = 1f
            set(value) { field = value; al { AL.alSourcef(source, AL.AL_PITCH, value) } }
        override var gain: Float = 1f
            set(value) {
                val value = maxOf(value, 0f)
                field = value; al { AL.alSourcef(source, AL.AL_GAIN, value) } }
        override var dataRate: Int = 44100
            //set(value) { field = value; al { AL.alSourcei(source, AL.AL_FREQUENCY, value) } }
        override var nchannels: Int = 1
            //set(value) { field = value; al { AL.alSourcei(source, AL.AL_CHANNELS, value) } }
        override var position: Vector3 = Vector3.ZERO
            set(value) { field = value; al { AL.alSource3f(source, AL.AL_POSITION, value.x, value.y, value.z) } }
        override var velocity: Vector3 = Vector3.ZERO
            set(value) { field = value; al { AL.alSource3f(source, AL.AL_VELOCITY, value.x, value.y, value.z) } }
        override var direction: Vector3 = Vector3.ZERO
            set(value) { field = value; al { AL.alSource3f(source, AL.AL_DIRECTION, value.x, value.y, value.z) } }
        override var looping: Boolean = false
            set(value) { field = value; al { AL.alSourcei(source, AL.AL_LOOPING, if (value) AL.AL_TRUE else AL.AL_FALSE) } }

        override var samplesPosition: Long
            get() = AL.alGetSourcei(source, AL.AL_SAMPLE_OFFSET).toLong()
            set(value) {
                al { AL.alSourcei(source, AL.AL_SAMPLE_OFFSET, value.toInt()) }
            }
        override var data: Array<AudioSampleArray>? = null
            set(value) {
                field = value
                val data = value?.interleaved() ?: return
                val format = if (nchannels == 1) AL.AL_FORMAT_MONO16 else AL.AL_FORMAT_STEREO16
                val dataSizeBytes = data.size * 2
                //println(data.asShortArray().toList())
                //val dataRate = 48000
                //println("buffer=$buffer")
                //println("source=$source")
                //println("dataRate=$dataRate")
                //println("bytesSize=${data.size * 2}")
                //val bufferData = Buffer(data.size * 2, direct = true)
                //bufferData.setArrayLE(0, data.asShortArray())
                //al { AL.alBufferData(buffer, format, bufferData, data.size * 2, dataRate) }
                al { AL.alBufferData(buffer, format, data.asShortArray(), dataSizeBytes, dataRate) }
                al { AL.alSourcei(source, AL.AL_BUFFER, buffer) }
            }

        override val state: AudioSourceState
            get() {
                val result = AL.alGetSourceState(source)
                return when (result) {
                    AL.AL_INITIAL -> AudioSourceState.INITIAL
                    AL.AL_PLAYING -> AudioSourceState.PLAYING
                    AL.AL_PAUSED -> AudioSourceState.PAUSED
                    AL.AL_STOPPED -> AudioSourceState.STOPPED
                    else -> error("Invalid state: $result")
                }
            }

        private fun updateProps() {
            pitch = pitch
            gain = gain
            dataRate = dataRate
            nchannels = nchannels
            position = position
            velocity = velocity
            direction = direction
            looping = looping

            al { AL.alSourcef(source, AL.AL_ROLLOFF_FACTOR, 0.0f) }
            al { AL.alSourcei(source, AL.AL_SOURCE_RELATIVE, 1) }
        }

        override fun _play() {
            //AL.alSourcef(source, AL.AL_SEC_OFFSET, 0f)
            al { AL.alSourcePlay(source) }
            //println("PLAY")
        }

        private fun _pause() {
            al { AL.alSourcePause(source) }
            //println("PAUSE")
        }

        override fun _stop() {
            al { AL.alSourceStop(source) }
            //println("STOP")
        }

        override fun close() {
            al { AL.alDeleteBuffer(buffer) }
            al { AL.alDeleteSource(source) }
            //println("CLOSE")
        }

        inline fun <T> al(block: () -> T): T? {
            return runCatchingAl {
                player.makeCurrent()
                AL.alGetError()
                val result = block()
                val error = AL.alGetError()
                if (error != AL.AL_NO_ERROR) {
                    Error("AL error: ${AL.alGetErrorName(error)} :: $error").printStackTrace()
                }
                result
            }
        }

        init {
            updateProps()
        }
    }
}


internal object AL : FFILib(nativeOpenALLibraryPath) {
    val alDopplerFactor: (value: Float) -> Unit by func()
    val alDopplerVelocity: (value: Float) -> Unit by func()
    val alSpeedOfSound: (value: Float) -> Unit by func()
    val alDistanceModel: (distanceModel: Int) -> Unit by func()
    val alEnable: (capability: Int) -> Unit by func()
    val alDisable: (capability: Int) -> Unit by func()
    val alIsEnabled: (capability: Int) -> Boolean by func()
    val alGetString: (param: Int) -> String by func()
    val alGetBooleanv: (param: Int, values: BooleanArray) -> Unit by func()
    val alGetIntegerv: (param: Int, values: IntArray) -> Unit by func()
    val alGetFloatv: (param: Int, values: FloatArray) -> Unit by func()
    val alGetDoublev: (param: Int, values: DoubleArray) -> Unit by func()
    val alGetBoolean: (param: Int) -> Boolean by func()
    val alGetInteger: (param: Int) -> Int by func()
    val alGetFloat: (param: Int) -> Float by func()
    val alGetDouble: (param: Int) -> Double by func()
    val alGetError: () -> Int by func()
    val alIsExtensionPresent: (extname: String) -> Boolean by func()
    val alGetProcAddress: (fname: String) -> FFIPointer by func()
    val alGetEnumValue: (ename: String) -> Int by func()
    val alListenerf: (param: Int, value: Float) -> Unit by func()
    val alListener3f: (param: Int, value1: Float, value2: Float, value3: Float) -> Unit by func()
    val alListenerfv: (param: Int, values: FloatArray) -> Unit by func()
    val alListeneri: (param: Int, value: Int) -> Unit by func()
    val alListener3i: (param: Int, value1: Int, value2: Int, value3: Int) -> Unit by func()
    val alListeneriv: (param: Int, values: IntArray) -> Unit by func()
    val alGetListenerf: (param: Int, value: FloatArray) -> Unit by func()
    val alGetListener3f: (param: Int, value1: FloatArray, value2: FloatArray, value3: FloatArray) -> Unit by func()
    val alGetListenerfv: (param: Int, values: FloatArray) -> Unit by func()
    val alGetListeneri: (param: Int, value: IntArray) -> Unit by func()
    val alGetListener3i: (param: Int, value1: IntArray, value2: IntArray, value3: IntArray) -> Unit by func()
    val alGetListeneriv: (param: Int, values: IntArray) -> Unit by func()
    val alGenSources: (n: Int, sources: IntArray) -> Unit by func()
    val alDeleteSources: (n: Int, sources: IntArray) -> Unit by func()
    val alIsSource: (source: Int) -> Boolean by func()
    val alSourcef: (source: Int, param: Int, value: Float) -> Unit by func()
    val alSource3f: (source: Int, param: Int, value1: Float, value2: Float, value3: Float) -> Unit by func()
    val alSourcefv: (source: Int, param: Int, values: FloatArray) -> Unit by func()
    val alSourcei: (source: Int, param: Int, value: Int) -> Unit by func()
    val alSource3i: (source: Int, param: Int, value1: Int, value2: Int, value3: Int) -> Unit by func()
    val alSourceiv: (source: Int, param: Int, values: IntArray) -> Unit by func()
    val alGetSourcef: (source: Int, param: Int, value: FloatArray) -> Unit by func()
    val alGetSource3f: (source: Int, param: Int, value1: FloatArray, value2: FloatArray, value3: FloatArray) -> Unit by func()
    val alGetSourcefv: (source: Int, param: Int, values: FloatArray) -> Unit by func()
    val alGetSourcei: (source: Int, param: Int, value: IntArray) -> Unit by func()
    val alGetSource3i: (source: Int, param: Int, value1: IntArray, value2: IntArray, value3: IntArray) -> Unit by func()
    val alGetSourceiv: (source: Int, param: Int, values: IntArray) -> Unit by func()
    val alSourcePlayv: (n: Int, sources: IntArray) -> Unit by func()
    val alSourceStopv: (n: Int, sources: IntArray) -> Unit by func()
    val alSourceRewindv: (n: Int, sources: IntArray) -> Unit by func()
    val alSourcePausev: (n: Int, sources: IntArray) -> Unit by func()
    val alSourcePlay: (source: Int) -> Unit by func()
    val alSourceStop: (source: Int) -> Unit by func()
    val alSourceRewind: (source: Int) -> Unit by func()
    val alSourcePause: (source: Int) -> Unit by func()
    val alSourceQueueBuffers: (source: Int, nb: Int, buffers: IntArray) -> Unit by func()
    val alSourceUnqueueBuffers: (source: Int, nb: Int, buffers: IntArray) -> Unit by func()
    val alGenBuffers: (n: Int, buffers: IntArray) -> Unit by func()
    val alDeleteBuffers: (n: Int, buffers: IntArray) -> Unit by func()
    val alIsBuffer: (buffer: Int) -> Boolean by func()
    //val alBufferData: (buffer: Int, format: Int, data: FFIPointer?, size: Int, freq: Int) -> Unit by func()
    val alBufferData: (buffer: Int, format: Int, data: ShortArray?, size: Int, freq: Int) -> Unit by func()
    //val alBufferData: (buffer: Int, format: Int, data: Buffer?, size: Int, freq: Int) -> Unit by func()
    val alBufferf: (buffer: Int, param: Int, value: Float) -> Unit by func()
    val alBuffer3f: (buffer: Int, param: Int, value1: Float, value2: Float, value3: Float) -> Unit by func()
    val alBufferfv: (buffer: Int, param: Int, values: FloatArray) -> Unit by func()
    val alBufferi: (buffer: Int, param: Int, value: Int) -> Unit by func()
    val alBuffer3i: (buffer: Int, param: Int, value1: Int, value2: Int, value3: Int) -> Unit by func()
    val alBufferiv: (buffer: Int, param: Int, values: IntArray) -> Unit by func()
    val alGetBufferf: (buffer: Int, param: Int, value: FloatArray) -> Unit by func()
    val alGetBuffer3f: (buffer: Int, param: Int, value1: FloatArray, value2: FloatArray, value3: FloatArray) -> Unit by func()
    val alGetBufferfv: (buffer: Int, param: Int, values: FloatArray) -> Unit by func()
    val alGetBufferi: (buffer: Int, param: Int, value: IntArray) -> Unit by func()
    val alGetBuffer3i: (buffer: Int, param: Int, value1: IntArray, value2: IntArray, value3: IntArray) -> Unit by func()
    val alGetBufferiv: (buffer: Int, param: Int, values: IntArray) -> Unit by func()

    private val tempF = FloatArray(1)
    private val tempI = IntArray(1)

    fun alGenBuffer(): Int = tempI.also { alGenBuffers(1, it) }[0]
    fun alGenSource(): Int = tempI.also { alGenSources(1, it) }[0]
    fun alDeleteBuffer(buffer: Int) { alDeleteBuffers(1, tempI.also { it[0] = buffer }) }
    fun alDeleteSource(buffer: Int) { alDeleteSources(1, tempI.also { it[0] = buffer }) }
    fun alGetSourcef(source: Int, param: Int): Float = tempF.also { alGetSourcef(source, param, it) }[0]
    fun alGetSourcei(source: Int, param: Int): Int = tempI.also { alGetSourcei(source, param, it) }[0]
    fun alGetSourceState(source: Int): Int = alGetSourcei(source, AL.AL_SOURCE_STATE)

    fun alGetListenerf(param: Int): Float = tempF.also { alGetListenerf(param, it) }[0]
    fun alGetListeneri(param: Int): Int = tempI.also { alGetListeneri(param, it) }[0]

    const val AL_NONE = 0
    const val AL_FALSE = 0
    const val AL_TRUE = 1
    const val AL_SOURCE_RELATIVE = 0x202
    const val AL_CONE_INNER_ANGLE = 0x1001
    const val AL_CONE_OUTER_ANGLE = 0x1002
    const val AL_PITCH = 0x1003
    const val AL_POSITION = 0x1004
    const val AL_DIRECTION = 0x1005
    const val AL_VELOCITY = 0x1006
    const val AL_LOOPING = 0x1007
    const val AL_BUFFER = 0x1009
    const val AL_GAIN = 0x100A
    const val AL_MIN_GAIN = 0x100D
    const val AL_MAX_GAIN = 0x100E
    const val AL_ORIENTATION = 0x100F
    const val AL_SOURCE_STATE = 0x1010
    const val AL_INITIAL = 0x1011
    const val AL_PLAYING = 0x1012
    const val AL_PAUSED = 0x1013
    const val AL_STOPPED = 0x1014
    const val AL_BUFFERS_QUEUED = 0x1015
    const val AL_BUFFERS_PROCESSED = 0x1016
    const val AL_REFERENCE_DISTANCE = 0x1020
    const val AL_ROLLOFF_FACTOR = 0x1021
    const val AL_CONE_OUTER_GAIN = 0x1022
    const val AL_MAX_DISTANCE = 0x1023
    const val AL_SEC_OFFSET = 0x1024
    const val AL_SAMPLE_OFFSET = 0x1025
    const val AL_BYTE_OFFSET = 0x1026
    const val AL_SOURCE_TYPE = 0x1027
    const val AL_STATIC = 0x1028
    const val AL_STREAMING = 0x1029
    const val AL_UNDETERMINED = 0x1030
    const val AL_FORMAT_MONO8 = 0x1100
    const val AL_FORMAT_MONO16 = 0x1101
    const val AL_FORMAT_STEREO8 = 0x1102
    const val AL_FORMAT_STEREO16 = 0x1103
    const val AL_FREQUENCY = 0x2001
    const val AL_BITS = 0x2002
    const val AL_CHANNELS = 0x2003
    const val AL_SIZE = 0x2004
    const val AL_UNUSED = 0x2010
    const val AL_PENDING = 0x2011
    const val AL_PROCESSED = 0x2012
    const val AL_NO_ERROR = 0
    const val AL_INVALID_NAME = 0xA001
    const val AL_INVALID_ENUM = 0xA002
    const val AL_INVALID_VALUE = 0xA003
    const val AL_INVALID_OPERATION = 0xA004
    const val AL_OUT_OF_MEMORY = 0xA005
    const val AL_VENDOR = 0xB001
    const val AL_VERSION = 0xB002
    const val AL_RENDERER = 0xB003
    const val AL_EXTENSIONS = 0xB004
    const val AL_DOPPLER_FACTOR = 0xC000
    const val AL_DOPPLER_VELOCITY = 0xC001
    const val AL_SPEED_OF_SOUND = 0xC003
    const val AL_DISTANCE_MODEL = 0xD000
    const val AL_INVERSE_DISTANCE = 0xD001
    const val AL_INVERSE_DISTANCE_CLAMPED = 0xD002
    const val AL_LINEAR_DISTANCE = 0xD003
    const val AL_LINEAR_DISTANCE_CLAMPED = 0xD004
    const val AL_EXPONENT_DISTANCE = 0xD005
    const val AL_EXPONENT_DISTANCE_CLAMPED = 0xD006

    // ALC

    val alcCreateContext: (device: FFIPointer, attrlist: IntArray?) -> FFIPointer? by func()
    val alcMakeContextCurrent: (context: FFIPointer?) -> Boolean by func()
    val alcProcessContext: (context: FFIPointer) -> Unit by func()
    val alcSuspendContext: (context: FFIPointer) -> Unit by func()
    val alcDestroyContext: (context: FFIPointer) -> Unit by func()
    val alcGetCurrentContext: () -> FFIPointer by func()
    val alcGetContextsDevice: (context: FFIPointer) -> FFIPointer by func()
    val alcOpenDevice: (devicename: String?) -> FFIPointer? by func()
    val alcCloseDevice: (device: FFIPointer?) -> Boolean by func()
    val alcGetError: (device: FFIPointer?) -> Int by func()
    val alcIsExtensionPresent: (device: FFIPointer?, extname: String) -> Boolean by func()
    val alcGetProcAddress: (device: FFIPointer?, funcname: String) -> FFIPointer by func()
    val alcGetEnumValue: (device: FFIPointer?, enumname: String) -> Int by func()
    val alcGetString: (device: FFIPointer?, param: Int) -> String? by func()
    val alcGetIntegerv: (device: FFIPointer?, param: Int, size: Int, values: IntArray) -> Unit by func()
    val alcCaptureOpenDevice: (devicename: String, frequency: Int, format: Int, buffersize: Int) -> FFIPointer by func()
    val alcCaptureCloseDevice: (device: FFIPointer) -> Boolean by func()
    val alcCaptureStart: (device: FFIPointer) -> Unit by func()
    val alcCaptureStop: (device: FFIPointer) -> Unit by func()
    val alcCaptureSamples: (device: FFIPointer, buffer: Buffer, samples: Int) -> Unit by func()

    val alcGetStringAsPointer: (device: FFIPointer?, param: Int) -> FFIPointer? by func("alcGetString")

    fun alcGetInteger(device: FFIPointer?, param: Int): Int {
        val ints = IntArray(1)
        alcGetIntegerv(device, param, 1, ints)
        return ints[0]
    }

    fun alGetErrorName(error: Int = alGetError()): String = when (error) {
        AL_NO_ERROR -> "AL_NO_ERROR"
        AL_INVALID_NAME -> "AL_INVALID_NAME"
        AL_INVALID_ENUM -> "AL_INVALID_ENUM"
        AL_INVALID_VALUE -> "AL_INVALID_VALUE"
        AL_INVALID_OPERATION -> "AL_INVALID_OPERATION"
        AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY"
        else -> "UNKNOWN: $error"
    }

    fun alcGetErrorName(device: FFIPointer?): String = alcGetErrorName(alcGetError(device))

    fun alcGetErrorName(error: Int): String = when (error) {
        AL.ALC_NO_ERROR -> "ALC_NO_ERROR"
        AL.ALC_INVALID_DEVICE -> "ALC_INVALID_DEVICE"
        AL.ALC_INVALID_CONTEXT -> "ALC_INVALID_CONTEXT"
        AL.ALC_INVALID_ENUM -> "ALC_INVALID_ENUM"
        AL.ALC_INVALID_VALUE -> "ALC_INVALID_VALUE"
        AL.ALC_OUT_OF_MEMORY -> "ALC_OUT_OF_MEMORY"
        else -> "UNKNOWN: $error"
    }

    const val ALC_FALSE = 0
    const val ALC_TRUE = 1
    const val ALC_FREQUENCY = 0x1007
    const val ALC_REFRESH = 0x1008
    const val ALC_SYNC = 0x1009
    const val ALC_MONO_SOURCES = 0x1010
    const val ALC_STEREO_SOURCES = 0x1011
    const val ALC_NO_ERROR = 0
    const val ALC_INVALID_DEVICE = 0xA001
    const val ALC_INVALID_CONTEXT = 0xA002
    const val ALC_INVALID_ENUM = 0xA003
    const val ALC_INVALID_VALUE = 0xA004
    const val ALC_OUT_OF_MEMORY = 0xA005
    const val ALC_MAJOR_VERSION = 0x1000
    const val ALC_MINOR_VERSION = 0x1001
    const val ALC_ATTRIBUTES_SIZE = 0x1002
    const val ALC_ALL_ATTRIBUTES = 0x1003
    const val ALC_DEFAULT_DEVICE_SPECIFIER = 0x1004
    const val ALC_DEVICE_SPECIFIER = 0x1005
    const val ALC_EXTENSIONS = 0x1006
    const val ALC_EXT_CAPTURE = 1
    const val ALC_CAPTURE_DEVICE_SPECIFIER = 0x310
    const val ALC_CAPTURE_DEFAULT_DEVICE_SPECIFIER = 0x311
    const val ALC_CAPTURE_SAMPLES = 0x312
    const val ALC_ENUMERATE_ALL_EXT = 1
    const val ALC_DEFAULT_ALL_DEVICES_SPECIFIER = 0x1012
    const val ALC_ALL_DEVICES_SPECIFIER = 0x1013

    //internal var loaded = false

    //init {
    //    try {
    //        if (nativeOpenALLibraryPath == null) error("Can't get OpenAL library")
    //        traceTime("OpenAL Native.register") {
    //            Native.register(nativeOpenALLibraryPath)
    //        }
    //        loaded = true
    //    } catch (e: Throwable) {
    //        com.soywiz.klogger.Console.error("Failed to initialize OpenAL: arch=$arch, OS.rawName=${OS.rawName}, nativeOpenALLibraryPath=$nativeOpenALLibraryPath, message=${e.message}")
    //        //e.printStackTrace()
    //    }
    //}
}

internal val nativeOpenALLibraryPath: String? by lazy {
    Envs["OPENAL_LIB_PATH"]?.let { path ->
        return@lazy path
    }
    if (Envs["KORAU_JVM_DUMMY_SOUND"] == "true") {
        return@lazy null
    }
    when {
        Platform.isMac -> "OpenAL" // Mac already includes the OpenAL library
        Platform.isLinux -> {
            when {
                Platform.arch.isArm -> getNativeFileLocalPath("natives/linuxarm/libopenal.so")
                Platform.arch.is64Bits -> getNativeFileLocalPath("natives/linuxx64/libopenal.so")
                else -> getNativeFileLocalPath("natives/linuxx86/libopenal.so")
            }
        }
        Platform.isWindows -> {
            when {
                Platform.arch.is64Bits -> getNativeFileLocalPath("natives/winx64/soft_oal.dll")
                else -> getNativeFileLocalPath("natives/winx86/soft_oal.dll")
            }
        }
        else -> {
            println("  - Unknown/Unsupported OS")
            null
        }
    }
}

private fun getNativeFileLocalPath(path: String): String {
    //val tempFile = File.createTempFile("libopenal_", ".${File(path).extension}")
    val tempFile = "${Environment.tempPath}${Environment.DIR_SEPARATOR}korau_openal.${path.substringAfterLast('.')}"

    val expectedSize = SyncSystemFS.getResourceLength(path, AL::class)

    if (!SyncSystemFS.exists(tempFile) || SyncSystemFS.size(tempFile) != expectedSize) {
        try {
            SyncSystemFS.writeBytes(tempFile, SyncSystemFS.getResourceBytes(path, AL::class))
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
    return tempFile
}

internal inline fun <T> runCatchingAl(block: () -> T): T? {
    val result = runCatching { block() }
    if (result.isFailure) {
        result.exceptionOrNull()?.printStackTrace()
    }
    return result.getOrNull()
}
