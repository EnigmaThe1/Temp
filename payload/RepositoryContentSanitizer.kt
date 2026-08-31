package com.llmcouncil.mobile.domain

import java.security.MessageDigest

object RepositoryContentSanitizer {
    data class Result(val text:String,val redactionCount:Int)

    private val patterns = listOf(
        Regex("(?im)(api[_-]?key|secret|token|password|passwd|pwd|client[_-]?secret|access[_-]?key)\\s*[:=]\\s*['\\\"]?([^'\\\"\\s]{8,})") to "$1=[REDACTED]",
        Regex("(?i)sk-[A-Za-z0-9_-]{16,}") to "[REDACTED_OPENAI_KEY]",
        Regex("(?i)sk-ant-[A-Za-z0-9_-]{16,}") to "[REDACTED_ANTHROPIC_KEY]",
        Regex("(?i)AIza[0-9A-Za-z_-]{20,}") to "[REDACTED_GOOGLE_KEY]",
        Regex("(?i)gh[pousr]_[A-Za-z0-9]{20,}") to "[REDACTED_GITHUB_TOKEN]",
        Regex("(?i)AKIA[0-9A-Z]{16}") to "[REDACTED_AWS_ACCESS_KEY]",
        Regex("(?is)-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----.*?-----END (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----") to "[REDACTED_PRIVATE_KEY]",
        Regex("(?i)\\b(?:postgres(?:ql)?|mysql|mongodb(?:\\+srv)?|redis)://[^\\s'\\\"]+") to "[REDACTED_CONNECTION_STRING]",
        Regex("(?i)Bearer\\s+[A-Za-z0-9._~+/-]{20,}=*") to "Bearer [REDACTED_TOKEN]",
        Regex("(?i)eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}") to "[REDACTED_JWT]"
    )

    fun sanitize(input:String,maxChars:Int?=null):Result {
        var text=input.replace("\u0000","")
        var count=0
        for ((regex,replacement) in patterns) {
            val matches=regex.findAll(text).count()
            if(matches>0){ count+=matches; text=regex.replace(text,replacement) }
        }
        if(maxChars!=null && text.length>maxChars) text=text.take(maxChars)
        return Result(text,count)
    }

    fun sha256(value:String):String {
        val digest=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(""){"%02x".format(it)}
    }
}
