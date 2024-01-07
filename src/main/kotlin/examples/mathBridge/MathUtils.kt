/*
 * Copyright (c) 2023-2024 Rahim
 * All rights reserved.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package examples.mathBridge

import kotlin.math.*

fun eval(str: String): Double {
    return object : Any() {
        var pos = -1
        var ch = 0
        fun nextChar() {
            ch = if (++pos < str.length) str[pos].code else -1
        }

        fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parse(): Double {
            nextChar()
            val x = parseExpression()
            if (pos < str.length) throw RuntimeException("Unexpected " + ch.toChar())
            return x
        }

        // Grammar:
        // expression = term | expression `+` term | expression `-` term
        // term = factor | term `*` factor | term `/` factor
        // factor = `+` factor | `-` factor | `(` expression `)` | number
        //        | functionName `(` expression `)` | functionName factor
        //        | factor `^` factor
        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm() // addition
                    eat('-'.code) -> x -= parseTerm() // subtraction
                    else -> return x
                }
            }
        }

        fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor() // multiplication
                    eat('/'.code) -> x /= parseFactor() // division
                    else -> return x
                }
            }
        }

        fun parseFactor(): Double {
            if (eat('+'.code)) return +parseFactor() // unary plus
            if (eat('-'.code)) return -parseFactor() // unary minus
            var x: Double
            val startPos = pos
            if (eat('('.code)) { // parentheses
                x = parseExpression()
                if (!eat(')'.code)) throw RuntimeException("Missing ')'")
            } else if (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) { // numbers
                while (ch >= '0'.code && ch <= '9'.code || ch == '.'.code) nextChar()
                x = str.substring(startPos, pos).toDouble()
            } else if (ch >= 'a'.code && ch <= 'z'.code) { // functions
                while (ch >= 'a'.code && ch <= 'z'.code) nextChar()
                val func = str.substring(startPos, pos)
                if (eat('('.code)) {
                    x = parseExpression()
                    if (!eat(')'.code)) throw RuntimeException("Missing ')' after argument to $func")
                } else {
                    x = parseFactor()
                }
                x =
                    when (func) {
                        "sqrt" -> sqrt(x)
                        "sin" -> sin(Math.toRadians(x))
                        "cos" -> cos(Math.toRadians(x))
                        "tan" -> tan(Math.toRadians(x))
                        else -> throw RuntimeException(
                            "Unknown function $func"
                        )
                    }
            }
            else {
                throw RuntimeException("Unexpected " + ch.toChar())
            }
            if (eat('^'.code)) x = x.pow(parseFactor()) // exponentiation
            return x
        }
    }.parse()
}

fun main() {
    try {
        val expression = "(3+4)/2+sin(31)"
        val result = eval(expression)
        println("Result: $result")
    } catch (e: Exception) {
        println("Error: ${e.message}")
    }
}
