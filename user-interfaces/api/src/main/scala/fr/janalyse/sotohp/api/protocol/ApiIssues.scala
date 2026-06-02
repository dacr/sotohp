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

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.tapir.Schema

sealed trait ApiIssue extends Exception

object ApiIssue {
  given JsonValueCodec[ApiIssue] = JsonCodecMaker.make
}

case class ApiInvalidOrMissingInput(message: String) extends Exception(message) with ApiIssue
object ApiInvalidOrMissingInput {
  given JsonValueCodec[ApiInvalidOrMissingInput] = JsonCodecMaker.make
  given Schema[ApiInvalidOrMissingInput]         = Schema.derived[ApiInvalidOrMissingInput].name(Schema.SName("InvalidOrMissingInput"))
}

case class ApiInternalError(message: String) extends Exception(message) with ApiIssue
object ApiInternalError {
  given JsonValueCodec[ApiInternalError] = JsonCodecMaker.make
  given Schema[ApiInternalError]         = Schema.derived[ApiInternalError].name(Schema.SName("ErrorInternal"))
}

case class ApiResourceNotFound(message: String) extends Exception(message) with ApiIssue
object ApiResourceNotFound {
  given JsonValueCodec[ApiResourceNotFound] = JsonCodecMaker.make
  given Schema[ApiResourceNotFound]         = Schema.derived[ApiResourceNotFound].name(Schema.SName("ErrorResourceNotFound"))
}

case class ApiSecurityError(message: String) extends Exception(message) with ApiIssue
object ApiSecurityError {
  given JsonValueCodec[ApiSecurityError] = JsonCodecMaker.make
  given Schema[ApiSecurityError]         = Schema.derived[ApiSecurityError].name(Schema.SName("ErrorSecurity"))
}
