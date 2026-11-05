package app.plamotranslate

object Engine {
    init { System.loadLibrary("translator") }

    fun interface Partial { fun onPartial(bytes: ByteArray) }

    external fun nativeLoad(path: String): Boolean
    external fun nativeTranslate(prompt: ByteArray, maxNew: Int, cb: Partial): ByteArray
    external fun nativeCancel()
    external fun nativeFree()
}
