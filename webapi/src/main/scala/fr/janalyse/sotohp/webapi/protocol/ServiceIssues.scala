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
package fr.janalyse.sotohp.webapi.protocol

import zio.json.{DeriveJsonCodec, JsonCodec, jsonDiscriminator}

sealed trait ServiceIssue

object ServiceIssue {
  given JsonCodec[ServiceIssue] = DeriveJsonCodec.gen
}

case class ServiceInternalError() extends ServiceIssue
object ServiceInternalError {
  given JsonCodec[ServiceInternalError] = DeriveJsonCodec.gen
}
