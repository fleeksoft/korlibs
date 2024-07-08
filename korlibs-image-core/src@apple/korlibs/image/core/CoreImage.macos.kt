package korlibs.image.core

actual val CoreImageFormatProvider_default: CoreImageFormatProvider = AppleCoreImageFormatProvider

// @TODO: Use Apple decoder
object AppleCoreImageFormatProvider : CoreImageFormatProvider {
    override suspend fun info(data: ByteArray): CoreImageInfo =
        StbiCoreImageFormatProvider.info(data)

    override suspend fun decode(data: ByteArray): CoreImage =
        StbiCoreImageFormatProvider.decode(data)

    override suspend fun encode(image: CoreImage, format: CoreImageFormat, level: Double): ByteArray =
        StbiCoreImageFormatProvider.encode(image, format, level)
}
