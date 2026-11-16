package com.aoooa.adb.control

/** 依次初始化资源候选；初始化失败时释放已创建的实例。 */
internal object CodecCandidateSelector {
    fun <T : Any> select(
        candidates: List<String>,
        create: (String) -> T,
        initialize: (T) -> Unit,
        release: (T) -> Unit,
        diagnose: (String) -> Unit
    ): T? {
        for (name in candidates) {
            var candidate: T? = null
            try {
                val instance = create(name)
                candidate = instance
                initialize(instance)
                diagnose("codec_ready candidate=$name")
                return instance
            } catch (e: Exception) {
                candidate?.let { instance ->
                    try {
                        release(instance)
                    } catch (releaseError: Exception) {
                        diagnose("codec_release_failed candidate=$name type=${releaseError.javaClass.simpleName}")
                    }
                }
                diagnose("codec_candidate_failed candidate=$name type=${e.javaClass.simpleName}")
            }
        }
        return null
    }
}
