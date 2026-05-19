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

  (* The least character in the ordering. *)
  val minChar : char [@@prototype "minChar"]

  (* The greatest character in the ordering. *)
  val maxChar : char [@@prototype "maxChar"]

  (* The ordinal number of the largest character; equal to ord maxChar. *)
  val maxOrd : int [@@prototype "maxOrd"]

  (* Returns the ordinal number (code point) of the character c. *)
  val ord : char -> int [@@method] [@@prototype "ord c"]

  (* Returns the character with ordinal number i; raises Chr if i < 0
   * or i > maxOrd. *)
  val chr : int -> char [@@prototype "chr i"]

  (* Returns the character immediately following c in the ordering, or
   * raises Chr if c = maxChar. *)
  val succ : char -> char [@@method] [@@prototype "succ c"]

  (* Returns the character immediately preceding c in the ordering, or
   * raises Chr if c = minChar. *)
  val pred : char -> char [@@method] [@@prototype "pred c"]

  (* Returns the lexicographic ordering of the two characters. *)
  val compare : char * char -> `order` [@@method] [@@prototype "compare (c1, c2)"]

  (* Lexicographic comparison of characters. *)
  val `<`  : char * char -> bool [@@prototype "c1 < c2"]
  val `<=` : char * char -> bool [@@prototype "c1 <= c2"]
  val `>`  : char * char -> bool [@@prototype "c1 > c2"]
  val `>=` : char * char -> bool [@@prototype "c1 >= c2"]

  (* Returns true if the character c occurs in the string s. *)
  val contains : string -> char -> bool [@@prototype "contains s c"]

  (* Returns true if the character c does not occur in the string s. *)
  val notContains : string -> char -> bool [@@prototype "notContains s c"]

  (* Returns true if c is a (seven-bit) ASCII character. *)
  val isAscii : char -> bool [@@method] [@@prototype "isAscii c"]

  (* Returns the lowercase letter corresponding to c if c is uppercase,
   * otherwise returns c. *)
  val toLower : char -> char [@@method] [@@prototype "toLower c"]

  (* Returns the uppercase letter corresponding to c if c is lowercase,
   * otherwise returns c. *)
  val toUpper : char -> char [@@method] [@@prototype "toUpper c"]

  (* Returns true if c is a letter (uppercase or lowercase). *)
  val isAlpha : char -> bool [@@method] [@@prototype "isAlpha c"]

  (* Returns true if c is alphanumeric (a letter or decimal digit). *)
  val isAlphaNum : char -> bool [@@method] [@@prototype "isAlphaNum c"]

  (* Returns true if c is a control character. *)
  val isCntrl : char -> bool [@@method] [@@prototype "isCntrl c"]

  (* Returns true if c is a decimal digit [0-9]. *)
  val isDigit : char -> bool [@@method] [@@prototype "isDigit c"]

  (* Returns true if c is a graphical character (printable and not a space). *)
  val isGraph : char -> bool [@@method] [@@prototype "isGraph c"]

  (* Returns true if c is a hexadecimal digit [0-9a-fA-F]. *)
  val isHexDigit : char -> bool [@@method] [@@prototype "isHexDigit c"]

  (* Returns true if c is a lowercase letter. *)
  val isLower : char -> bool [@@method] [@@prototype "isLower c"]

  (* Returns true if c is a printable character (including space). *)
  val isPrint : char -> bool [@@method] [@@prototype "isPrint c"]

  (* Returns true if c is a whitespace character. *)
  val isSpace : char -> bool [@@method] [@@prototype "isSpace c"]

  (* Returns true if c is a punctuation character (graphical but not
   * alphanumeric). *)
  val isPunct : char -> bool [@@method] [@@prototype "isPunct c"]

  (* Returns true if c is an uppercase letter. *)
  val isUpper : char -> bool [@@method] [@@prototype "isUpper c"]

  (* Returns a printable string representation of the character. *)
  val toString : char -> (*String.*)string [@@method] [@@prototype "toString c"]
(*
  val scan       : (Char.char, 'a) StringCvt.reader
                   -> (char, 'a) StringCvt.reader
*)
  (* Scans a character from a string, returning SOME c or NONE. *)
  val fromString : (*String.*)string -> char option [@@prototype "fromString s"]

  (* Returns a string corresponding to the C-language representation of
   * the character. *)
  val toCString : char -> (*String.*)string [@@method] [@@prototype "toCString c"]

  (* Scans a C-language character escape sequence from a string. *)
  val fromCString : (*String.*)string -> char option [@@prototype "fromCString s"]
end

(*) End char.sig
