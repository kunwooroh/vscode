package com.kunwooroh.gemmaagent.tools

/**
 * 사칙연산 계산기. 작은 온디바이스 모델은 계산이 약하므로 도구로 정확하게 계산한다.
 * 지원: + - * / % ^ 괄호, 소수점, 천 단위 쉼표(무시)
 */
object Calculator {
  fun eval(expression: String): Double {
    val src =
      expression
        .replace(",", "")
        .replace("×", "*")
        .replace("÷", "/")
        .replace("x", "*")
        .replace("X", "*")
        .replace(" ", "")
    val parser = Parser(src)
    val result = parser.parseExpression()
    if (parser.pos < src.length) {
      throw IllegalArgumentException("해석할 수 없는 문자: '${src[parser.pos]}'")
    }
    return result
  }

  private class Parser(val s: String) {
    var pos = 0

    fun parseExpression(): Double {
      var v = parseTerm()
      while (pos < s.length) {
        when (s[pos]) {
          '+' -> { pos++; v += parseTerm() }
          '-' -> { pos++; v -= parseTerm() }
          else -> return v
        }
      }
      return v
    }

    fun parseTerm(): Double {
      var v = parsePower()
      while (pos < s.length) {
        when (s[pos]) {
          '*' -> { pos++; v *= parsePower() }
          '/' -> {
            pos++
            val d = parsePower()
            if (d == 0.0) throw ArithmeticException("0으로 나눌 수 없습니다")
            v /= d
          }
          '%' -> { pos++; v %= parsePower() }
          else -> return v
        }
      }
      return v
    }

    fun parsePower(): Double {
      val base = parseUnary()
      if (pos < s.length && s[pos] == '^') {
        pos++
        return Math.pow(base, parsePower())
      }
      return base
    }

    fun parseUnary(): Double {
      if (pos < s.length && s[pos] == '-') { pos++; return -parseUnary() }
      if (pos < s.length && s[pos] == '+') { pos++; return parseUnary() }
      return parsePrimary()
    }

    fun parsePrimary(): Double {
      if (pos < s.length && s[pos] == '(') {
        pos++
        val v = parseExpression()
        if (pos >= s.length || s[pos] != ')') throw IllegalArgumentException("괄호가 닫히지 않았습니다")
        pos++
        return v
      }
      val start = pos
      while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
      if (start == pos) throw IllegalArgumentException("숫자가 필요합니다 (위치 $pos)")
      return s.substring(start, pos).toDouble()
    }
  }
}
