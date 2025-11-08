/*
 * Copyright 2022 David Crosson
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fr.janalyse.sotohp.api.protocol

import sttp.tapir.Schema
import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}

sealed trait ApiIssue extends Exception

object ApiIssue {
  given JsonCodec[ApiIssue] = DeriveJsonCodec.gen
}

case class ApiInvalidOrMissingInput(message: String) extends Exception(message) with ApiIssue
object ApiInvalidOrMissingInput {
  given JsonCodec[ApiInvalidOrMissingInput] = DeriveJsonCodec.gen
  given Schema[ApiInvalidOrMissingInput]    = Schema.derived[ApiInvalidOrMissingInput].name(Schema.SName("InvalidOrMissingInput"))
}

case class ApiInternalError(message: String) extends Exception(message) with ApiIssue
object ApiInternalError {
  given JsonCodec[ApiInternalError] = DeriveJsonCodec.gen
  given Schema[ApiInternalError]    = Schema.derived[ApiInternalError].name(Schema.SName("ErrorInternal"))
}

case class ApiResourceNotFound(message: String) extends Exception(message) with ApiIssue
object ApiResourceNotFound {
  given JsonCodec[ApiResourceNotFound] = DeriveJsonCodec.gen
  given Schema[ApiResourceNotFound]    = Schema.derived[ApiResourceNotFound].name(Schema.SName("ErrorResourceNotFound"))
}
