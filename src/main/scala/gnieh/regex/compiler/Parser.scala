/*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package gnieh.regex
package compiler

import util._

import scala.util.{
  Try,
  Success,
  Failure
}

import scala.annotation.tailrec

class RegexParserException(val offset: Int, msg: String) extends Exception(msg) {

  override def getMessage(): String =
    s"$msg near index $offset"

}

/** Parses a regular expression, accepts POSIX Extended Regular Expression syntax.
 *  See [[gnieh.regex.Regex]] for the accepted syntax.
 *
 *  @author Lucas Satabin
 */
object Parser {

  private sealed trait Token
  private case object DOT extends Token
  private case object STAR extends Token
  private case object PLUS extends Token
  private case object OPT extends Token
  private case object PIPE extends Token
  private case object LPAR extends Token
  private case object RPAR extends Token
  private case object LBRACKET extends Token
  private case object RBRACKET extends Token
  private case object LBRACE extends Token
  private case object RBRACE extends Token
  private case object CIRC extends Token
  private case object DOLLAR extends Token
  private final case class NUMBER_CLASS(negated: Boolean) extends Token
  private final case class WORD_CLASS(negated: Boolean) extends Token
  private final case class SPACE_CLASS(negated: Boolean) extends Token
  private case object EOI extends Token
  private final case class CHAR(c: Char) extends Token

  // the lexer state is different when in different parts of the regular exception:
  //  - parsing some normal part of the regular expression
  //  - parsing a repetition boundary
  //  - parsing a character state
  private sealed trait LexState
  private case object NormalState extends LexState
  private case object BoundState extends LexState
  private final case class SetState(negated: Boolean, previous: LexState) extends LexState

  // offset representing the current position in the input
  private type Offset = Int

  private val NoOffset: Offset = -1

  // stack of already parsed regular expression parts
  private type Stack = List[ReNode]

  def parse(input: String): Try[ReNode] = {
    @tailrec
    def loop(state: LexState, level: Int, stack: Stack, offset: Int): Try[Stack] =
      if (offset >= input.length) {
        Success(stack)
      } else {
        // do not use map here to have a tail recuvrsive function
        parseRe(input, state, level, stack, offset) match {
          case Success((newState, newLevel, newStack, newOffset)) =>
            //println(newStack)
            loop(newState, newLevel, newStack, newOffset)
          case Failure(e) =>
            Failure(e)
        }
      }

    loop(NormalState, 0, Nil, 0).flatMap { stack =>
      reduceAlternatives(0, stack, input.length).flatMap {
        case List(node) =>
          Success(node)
        case _ =>
          Failure(new RegexParserException(0, "Malformed regular expression"))
      }
    } recoverWith {
      case exn: RegexParserException =>
        Failure(new RuntimeException(s"""${exn.getMessage}
                                          |${input.substring(exn.offset)}
                                          |^""".stripMargin))
    }
  }

  // Some built-in character classes
  private lazy val anyChar = CharRangeSet(CharRange(Char.MinValue, Char.MaxValue))
  private lazy val digit = CharRangeSet(CharRange('0', '9'))
  private lazy val nonDigit = digit.negate
  private lazy val alphaNum = CharRangeSet(CharRange('A', 'Z'), CharRange('a', 'z'), CharRange('0', '9'), CharRange('_'))
  private lazy val nonAlphaNum = alphaNum.negate
  private lazy val space = CharRangeSet(CharRange(' '), CharRange('\t'), CharRange('\r'), CharRange('\n'), CharRange('\f'))
  private lazy val nonSpace = space.negate

  /** Parses one regular expression, returning the new stack of parsed elements
   *  and the new offset if it succeeded
   */
  private def parseRe(input: String, state: LexState, level: Int, stack: Stack, offset: Offset): Try[(LexState, Int, Stack, Offset)] =
    nextToken(input, state, offset).flatMap {
      case (EOI, newOffset) =>
        Success(state, level, stack, newOffset)
      case (DOT, newOffset) =>
        Success(state, level, AnyChar :: stack, newOffset)
      case (CHAR(c), newOffset) =>
        Success(state, level, SomeChar(c) :: stack, newOffset)
      case (NUMBER_CLASS(negated), newOffset) =>
        // number class is syntactic sugar for [0-9]
        Success(state, level, CharSet(if (negated) nonDigit else digit) :: stack, newOffset)
      case (WORD_CLASS(negated), newOffset) =>
        // word class is syntactic sugar for [A-Za-z0-9_]
        Success(state, level, CharSet(if (negated) nonAlphaNum else alphaNum) :: stack, newOffset)
      case (SPACE_CLASS(negated), newOffset) =>
        // space class is syntactic sugar for [ \t\r\n\f]
        Success(state, level, CharSet(if (negated) nonSpace else space) :: stack, newOffset)
      case (STAR, newOffset) =>
        // zero or more repetition, we pop the last element from the stack and
        // push the new repeated one
        // determine whether it is greedy
        nextRawToken(input, newOffset) flatMap {
          case (CHAR('?'), newOffset) =>
            for (newStack <- reduceOne("*", stack, offset, Star(_, false)))
              yield (state, level, newStack, newOffset)
          case (_, _) =>
            for (newStack <- reduceOne("*", stack, offset, Star(_, true)))
              yield (state, level, newStack, newOffset)
        }
      case (PLUS, newOffset) =>
        // one or more repetition, we pop the last element from the stack and
        // push the new repeated one
        // determine whether it is greedy
        nextRawToken(input, newOffset) flatMap {
          case (CHAR('?'), newOffset) =>
            for (newStack <- reduceOne("+", stack, offset, Plus(_, false)))
              yield (state, level, newStack, newOffset)
          case (_, _) =>
            for (newStack <- reduceOne("+", stack, offset, Plus(_, true)))
              yield (state, level, newStack, newOffset)
        }
      case (OPT, newOffset) =>
        // optional element, we pop the last element from the stack and
        // push the new repeated one
        // determine whether it is greedy
        nextRawToken(input, newOffset) flatMap {
          case (CHAR('?'), newOffset) =>
            for (newStack <- reduceOne("?", stack, offset, Opt(_, false)))
              yield (state, level, newStack, newOffset)
          case (_, _) =>
            for (newStack <- reduceOne("?", stack, offset, Opt(_, true)))
              yield (state, level, newStack, newOffset)
        }
      case (LPAR, newOffset) =>
        // opening a capturing group, push the temporary marker onto the stack and keep going
        Success(state, level + 1, CapturingStart(level, offset) :: stack, newOffset)
      case (RPAR, newOffset) =>
        // closing capturing group, pop all elements until the matching opening temporary
        // node, and push the new captured one
        for (newStack <- reduceCapturing(level - 1, stack, offset))
          yield (state, level - 1, newStack, newOffset)
      case (PIPE, newOffset) =>
        // alternative, reduce until either an opening capturing group or another alternative
        for (newStack <- reduceAlternatives(level, stack, offset))
          yield (state, level, Alternative(offset) :: newStack, newOffset)
      case (LBRACKET, newOffset) =>
        // character set started
        nextRawToken(input, newOffset) map {
          case (CHAR('^'), newOffset) =>
            (SetState(true, state), level + 1, CharSetStart(level, offset) :: stack, newOffset)
          case (_, _) =>
            (SetState(false, state), level + 1, CharSetStart(level, offset) :: stack, newOffset)
        }
      case (RBRACKET, newOffset) =>
        state match {
          case SetState(negated, prevState) =>
            // character set ended, reduce the character set as an alternative between characters
            // reduce until the matching
            for (newStack <- reduceCharSet(negated, level - 1, stack, offset))
              yield (prevState, level - 1, newStack, newOffset)
          case _ =>
            Success(state, level, SomeChar(']') :: stack, newOffset)
        }
      case (CIRC, newOffset) =>
        Success(state, level, StartAnchor :: stack, newOffset)
      case (DOLLAR, newOffset) =>
        Success(state, level, EndAnchor :: stack, newOffset)
      case (LBRACE, newOffset) =>
        Success(BoundState, level, BoundStart(offset) :: stack, newOffset)
      case (RBRACE, newOffset) =>
        state match {
          case BoundState =>
            // boundary end, reduce the boundary and previous atom
            for (newStack <- reduceBounded(stack, offset))
              yield (NormalState, level, newStack, newOffset)
          case _ =>
            Success(state, level, SomeChar('}') :: stack, newOffset)
        }
    }

  /* Pops one element of the stack and pushes the newly contructed one from this one */
  private def reduceOne(meta: String, stack: Stack, offset: Offset, constr: ReNode => ReNode) =
    stack match {
      case (tmp: Temporary) :: tail =>
        Failure(new RegexParserException(tmp.offset, "Malformed regular expression"))
      case node :: tail =>
        Success(constr(node) :: tail)
      case Nil =>
        Failure(new RegexParserException(offset, s"Dangling control meta character '$meta'"))
    }

  /* Pops all elements from the stack until the matching temporary opening node, concatenate
   * the nodes, and pushes the new captured node */
  private def reduceCapturing(level: Int, stack: Stack, offset: Offset) = {
    @tailrec
    def loop(stack: Stack, acc: Option[ReNode]): Try[Stack] =
      stack match {
        case CapturingStart(`level`, _) :: tail =>
          // we found the matching opening node
          Success(Capture(acc.getOrElse(Empty)) :: tail)
        case second :: Alternative(_) :: first :: tail =>
          // reduce captured alternatives when encountering them
          loop(tail, Some(acc.fold(Alt(first, second))(a => Alt(first, Concat(second, a)))))
        case (tmp: Temporary) :: _ =>
          Failure(new RegexParserException(tmp.offset, "Malformed regular expression"))
        case node :: tail =>
          loop(tail, Some(acc.fold(node)(Concat(node, _))))
        case Nil =>
          Failure(new RegexParserException(offset, "Unbalanced closing character ')'"))

      }
    loop(stack, None)
  }

  /* Pops all elements from the stack until the matching temporary opening node,alternate
   * the nodes, and pushes the new alternative node */
  private def reduceCharSet(negated: Boolean, level: Int, stack: Stack, offset: Offset): Try[Stack] = {
    @tailrec
    def loop(stack: Stack, acc: CharRangeSet): Try[Stack] =
      stack match {
        case CharSetStart(`level`, _) :: tail =>
          // we found the matching opening node
          acc match {
            case AVL() =>
              Success(tail)
            case AVL(CharRange(c1, c2)) if c1 == c2 && !negated =>
              Success(SomeChar(c1) :: tail)
            case _ =>
              Success(CharSet(if (negated) acc.negate else acc) :: tail)
          }
        case (tmp: Temporary) :: _ =>
          Failure(new RegexParserException(tmp.offset, "Malformed regular expression"))
        case _ :: SomeChar('-') :: CharSetStart(`level`, off) :: _ =>
          // malformed range `[-a]'
          Failure(new RegexParserException(off + 1, "Malformed range"))
        case SomeChar(c1) :: SomeChar('-') :: SomeChar(c2) :: tail if c2 <= c1 =>
          // well-formed range
          loop(tail, acc + CharRange(c2, c1))
        case SomeChar(c) :: tail =>
          // any other character
          loop(tail, acc + CharRange(c))
        case CharSet(chars) :: tail =>
          loop(tail, acc ++ chars)
        case n :: tail =>
          Failure(new RegexParserException(offset, "Malformed character set"))
      }
    loop(stack, new CharRangeSet(Nil))
  }

  /* Pops all the elements from the stack until we reach an alternative or an opening group,
   * or the bottom of the stack */
  private def reduceAlternatives(level: Int, stack: Stack, offset: Offset) = {
    @tailrec
    def loop(stack: Stack, acc: Option[ReNode]): Try[Stack] =
      stack match {
        case Alternative(off) :: first :: tail =>
          Success(acc.fold(first)(Alt(first, _)) :: tail)
        case CapturingStart(lvl, _) :: tail if lvl == level - 1 =>
          Success(acc.getOrElse(Empty) :: stack)
        case (tmp: Temporary) :: _ =>
          Failure(new RegexParserException(tmp.offset, "Malformed regular expression"))
        case node :: tail =>
          loop(tail, Some(acc.fold(node)(Concat(node, _))))
        case Nil =>
          Success(List(acc.getOrElse(Empty)))
      }
    loop(stack, None)
  }

  /* Pops all the elements from the stack until we reach start of boundaries expression. */
  private def reduceBounded(stack: Stack, offset: Offset) = {
    @tailrec
    def boundaries(stack: Stack, inMax: Boolean, min: Option[Int], max: Option[Int]): Try[(Stack, Int, Option[Int])] =
      stack match {
        case BoundStart(off) :: tail =>
          (min, max) match {
            case (Some(min), _)    => Success(tail, min, max)
            case (None, Some(min)) => Success(tail, min, Some(min))
            case (None, None)      => Failure(new RegexParserException(off, "Malformed regular expression"))
          }
        case SomeChar(',') :: tail if inMax =>
          boundaries(tail, false, min, max)
        case SomeChar(n @ ('0' | '1' | '2' | '3' | '4' | '5' | '6' | '7' | '8' | '9')) :: tail =>
          if (inMax)
            boundaries(tail, inMax, min, max.orElse(Some(0)).map(_ * 10 + (n - 48)))
          else
            boundaries(tail, inMax, min.orElse(Some(0)).map(_ * 10 + (n - 48)), max)
        case _ =>
          Failure(new RegexParserException(offset, "Malformed regular expression"))
      }
    boundaries(stack, true, None, None).flatMap {
      case (newStack, min, max) =>
        val (n, newStack1) = newStack match {
          case n :: tail => (n, tail)
          case Nil       => (Empty, Nil)
        }
        max match {
          case Some(`min`) =>
            val n1 =
              (1 to min).foldLeft(Empty: ReNode) {
                case (acc, _) => Concat(acc, n)
              }
            Success(n1 :: newStack1)
          case Some(max) if max >= min =>
            val n1 =
              (1 to (max - min)).foldRight(Empty: ReNode) {
                case (_, acc) => Concat(Opt(n, true), acc)
              }
            val n2 =
              (1 to min).foldRight(n1) {
                case (_, acc) => Concat(n, acc)
              }
            Success(n2 :: newStack1)
          case Some(_) =>
            Failure(new RegexParserException(offset, "Malformed regular expression"))
          case None =>
            val n1 =
              (1 until min).foldRight(Plus(n, true): ReNode) {
                case (_, acc) => Concat(n, acc)
              }
            Success(n1 :: newStack1)
        }
    }
  }

  private def escapable(state: LexState, c: Char): Boolean =
    state match {
      case NormalState =>
        ".[{()\\*+?|".contains(c)
      case BoundState =>
        "}".contains(c)
      case SetState(_, _) =>
        "\\[]".contains(c)
    }

  private def classable(c: Char): Boolean =
    "dswDSW".contains(c)

  private def nextRawToken(input: String, offset: Offset): Try[(Token, Offset)] =
    if (offset >= input.size) {
      // EOI reached
      Success(EOI, offset)
    } else {
      // just return the read character
      Success(CHAR(input(offset)), offset + 1)
    }

  private def nextToken(input: String, state: LexState, offset: Offset): Try[(Token, Offset)] =
    if (offset >= input.size) {
      // EOI reached
      Success(EOI, offset)
    } else if (input(offset) == '\\') {
      if (offset + 1 < input.size) {
        // still something to read
        if (escapable(state, input(offset + 1))) {
          // escaped character
          Success(CHAR(input(offset + 1)), offset + 2)
        } else if (classable(input(offset + 1))) {
          // character class
          if (input(offset + 1) == 'd') {
            Success(NUMBER_CLASS(false), offset + 2)
          } else if (input(offset + 1) == 's') {
            Success(SPACE_CLASS(false), offset + 2)
          } else if (input(offset + 1) == 'w') {
            Success(WORD_CLASS(false), offset + 2)
          } else if (input(offset + 1) == 'D') {
            Success(NUMBER_CLASS(true), offset + 2)
          } else if (input(offset + 1) == 'S') {
            Success(SPACE_CLASS(true), offset + 2)
          } else if (input(offset + 1) == 'W') {
            Success(WORD_CLASS(true), offset + 2)
          } else {
            Failure(new RegexParserException(offset + 1, s"Unknown escaped character '\\${input(offset + 1)}'"))
          }
        } else {
          Failure(new RegexParserException(offset + 1, s"Unknown escaped character '\\${input(offset + 1)}'"))
        }
      } else {
        Failure(new RegexParserException(offset, "Unterminated escaped character"))
      }
    } else if (escapable(state, input(offset))) {
      if (input(offset) == '.')
        Success(DOT, offset + 1)
      else if (input(offset) == '*')
        Success(STAR, offset + 1)
      else if (input(offset) == '+')
        Success(PLUS, offset + 1)
      else if (input(offset) == '?')
        Success(OPT, offset + 1)
      else if (input(offset) == '|')
        Success(PIPE, offset + 1)
      else if (input(offset) == '(')
        Success(LPAR, offset + 1)
      else if (input(offset) == ')')
        Success(RPAR, offset + 1)
      else if (input(offset) == '[')
        Success(LBRACKET, offset + 1)
      else if (input(offset) == ']')
        Success(RBRACKET, offset + 1)
      else if (input(offset) == '{')
        Success(LBRACE, offset + 1)
      else if (input(offset) == '}')
        Success(RBRACE, offset + 1)
      else if (input(offset) == '^')
        Success(CIRC, offset + 1)
      else if (input(offset) == '$')
        Success(DOLLAR, offset + 1)
      else
        Success(CHAR(input(offset)), offset + 1)
    } else {
      Success(CHAR(input(offset)), offset + 1)
    }

}

