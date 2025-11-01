(*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 *
 * The CHAR signature, per the Standard ML Basis Library.
 *)
signature CHAR =
sig
eqtype char
eqtype string

val minChar : char
val maxChar : char
val maxOrd : int

val ord : char -> int
val chr : int -> char
val succ : char -> char
val pred : char -> char

val compare : char * char -> `order`
val `<`  : char * char -> bool
val `<=` : char * char -> bool
val `>`  : char * char -> bool
val `>=` : char * char -> bool

val contains : string -> char -> bool
val notContains : string -> char -> bool

val isAscii : char -> bool
val toLower : char -> char
val toUpper : char -> char
val isAlpha : char -> bool
val isAlphaNum : char -> bool
val isCntrl : char -> bool
val isDigit : char -> bool
val isGraph : char -> bool
val isHexDigit : char -> bool
val isLower : char -> bool
val isPrint : char -> bool
val isSpace : char -> bool
val isPunct : char -> bool
val isUpper : char -> bool

val toString : char -> (*String.*)string
(*
val scan       : (Char.char, 'a) StringCvt.reader
                   -> (char, 'a) StringCvt.reader
*)
val fromString : (*String.*)string -> char option
val toCString : char -> (*String.*)string
val fromCString : (*String.*)string -> char option
end
