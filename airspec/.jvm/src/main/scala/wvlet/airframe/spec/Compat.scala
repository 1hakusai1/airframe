/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.airframe.spec

import wvlet.airframe.surface.reflect.ReflectTypeUtil
import wvlet.log.LogFormatter.SourceCodeLogFormatter
import wvlet.log.Logger

import scala.util.Try

/**
  *
  */
object Compat {
  def findCompanionObjectOf(fullyQualifiedName: String, classLoader: ClassLoader): Option[Any] = {
    val cls = classLoader.loadClass(fullyQualifiedName)
    ReflectTypeUtil.companionObject(cls)
  }

  def newInstanceOf(fullyQualifiedName: String, classLoader: ClassLoader): Option[Any] = {
    Try(classLoader.loadClass(fullyQualifiedName).newInstance).toOption
  }

  private[spec] def withLogScanner[U](block: => U): U = {
    Logger.setDefaultFormatter(SourceCodeLogFormatter)

    // Periodically scan log level file
    Logger.scheduleLogLevelScan
    try {
      block
    } finally {
      Logger.stopScheduledLogLevelScan
    }
  }
}
