package com.company.skolab.utils

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class PhoneVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // Keep only digits
        val digits = text.text.filter { it.isDigit() }
        
        val trimmed = if (digits.length >= 10) digits.substring(0, 10) else digits
        val out = StringBuilder()
        for (i in trimmed.indices) {
            if (i == 0) out.append("(")
            out.append(trimmed[i])
            if (i == 2) out.append(") ")
            if (i == 5) out.append("-")
        }
        
        val phoneNumberOffsetTranslator = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 0
                var digitCount = 0
                var i = 0
                while (i < offset && i < text.text.length) {
                    if (text.text[i].isDigit()) {
                        digitCount++
                    }
                    i++
                }
                
                if (digitCount == 0) return 0
                var transformedOffset = 1 // for '('
                transformedOffset += digitCount
                if (digitCount > 2) transformedOffset += 2 // for ') '
                if (digitCount > 5) transformedOffset += 1 // for '-'
                
                val maxLen = out.length
                return if (transformedOffset > maxLen) maxLen else transformedOffset
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 0) return 0
                var digitCount = 0
                var i = 0
                while (i < offset && i < out.length) {
                    val char = out[i]
                    if (char.isDigit()) {
                        digitCount++
                    }
                    i++
                }
                
                var originalOffset = 0
                var countedDigits = 0
                while (originalOffset < text.text.length && countedDigits < digitCount) {
                    if (text.text[originalOffset].isDigit()) {
                        countedDigits++
                    }
                    originalOffset++
                }
                
                return originalOffset
            }
        }
        
        return TransformedText(AnnotatedString(out.toString()), phoneNumberOffsetTranslator)
    }
}
