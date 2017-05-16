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

import vm._
import util._

/** The regular expression compiler compiles tree based regular expressions into
 *  the target bytecode instructions
 *
 *  @author Lucas Satabin
 */
object Compiler {

  def compile(re: ReNode): (Int, Vector[Inst]) = {
    def loop(currentSave: Int, startIdx: Int, re: ReNode): (Int, Int, Vector[Inst]) = {
      re match {
        case Empty =>
          // match
          (currentSave, startIdx + 1, Vector(MatchFound))
        case SomeChar(c) =>
          // char c
          (currentSave, startIdx + 1, Vector(CharMatch(c)))
        case AnyChar =>
          // any
          (currentSave, startIdx + 1, Vector(AnyMatch))
        case Concat(e1, e2) =>
          // comp(e1)
          // comp(e2)
          val (currentSave1, idx1, v1) = loop(currentSave, startIdx, e1)
          val (currentSave2, idx2, v2) = loop(currentSave1, idx1, e2)
          (currentSave2, idx2, v1 ++ v2)
        case Alt(e1, e2) =>
          // split L1, L2
          // L1: comp(e1)
          // jump L3
          // L2: comp(e2)
          // L3:
          val (currentSave1, idx1, v1) = loop(currentSave, startIdx + 1, e1)
          val (currentSave2, idx2, v2) = loop(currentSave1, idx1 + 1, e2)
          (currentSave2, idx2, Vector(Split(startIdx + 1, idx1 + 1)) ++ v1 ++ Vector(Jump(idx2)) ++ v2)
        case Opt(e, true) =>
          // greedy version
          // split L1, L2
          // L1: comp(e)
          // L2:
          val (currentSave1, idx1, v1) = loop(currentSave, startIdx + 1, e)
          (currentSave1, idx1, Vector(Split(startIdx + 1, idx1)) ++ v1)
        case Opt(e, false) =>
          // non greedy version
          // split L2, L1
          // L1: comp(e)
          // L2:
          val (currentSave1, idx1, v1) = loop(currentSave, startIdx + 1, e)
          (currentSave1, idx1, Vector(Split(idx1, startIdx + 1)) ++ v1)
        case Star(e, true) =>
          // greedy version
          // L1: split L2, L3
          // L2: comp(e)
          // jump L1
          // L3:
          val (currentSave1, idx1, v1) = loop(currentSave, startIdx + 1, e)
          (currentSave1, idx1 + 1, Vector(Split(startIdx + 1, idx1 + 1)) ++ v1 ++ Vector(Jump(startIdx)))
        case Star(e, false) =>
          // non greedy version
          // L1: split L3, L2
          // L2: comp(e)
          // jump L1
          // L3:
          val (currentSave1, idx1, v1) = loop(currentSave, startIdx + 1, e)
          (currentSave1, idx1 + 1, Vector(Split(idx1 + 1, startIdx + 1)) ++ v1 ++ Vector(Jump(startIdx)))
        case Plus(e, true) =>
          // greedy version
          // L1: comp(e)
          // split L1, L2
          // L2:
          val (currentSave1, idx1, v1) = loop(currentSave, startIdx, e)
          (currentSave1, idx1 + 1, v1 ++ Vector(Split(startIdx, idx1 + 1)))
        case Plus(e, false) =>
          // non greedy version
          // L1: comp(e)
          // split L2, L1
          // L2:
          val (currentSave1, idx1, v1) = loop(currentSave, startIdx, e)
          (currentSave1, idx1 + 1, v1 ++ Vector(Split(idx1 + 1, startIdx)))
        case CharSet(ranges) =>
          // class ranges
          (currentSave, startIdx + 1, Vector(ClassMatch(ranges.toTree)))
        case Capture(e) =>
          // save n
          // comp(e)
          // save n + 1
          val (currentSave1, idx1, v1) = loop(currentSave + 2, startIdx + 1, e)
          (currentSave1, idx1 + 1, Vector(Save(currentSave)) ++ v1 ++ Vector(Save(currentSave + 1)))
        case StartAnchor =>
          // check_start
          (currentSave, startIdx + 1, Vector(CheckStart))
        case EndAnchor =>
          // check_end
          (currentSave, startIdx + 1, Vector(CheckEnd))
        case _ =>
          throw new RuntimeException("Should never happen")
      }
    }
    val (saved, _, inst) = loop(0, 0, re)
    (saved / 2, inst ++ Vector(MatchFound))
  }

}

