/*
 * Copyright 2011-2019 GatlingCorp (https://gatling.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.gatling.core.check.extractor.jmespath

import io.gatling.commons.validation._
import io.gatling.core.check.extractor.jsonpath.JsonFilter
import io.gatling.core.config.GatlingConfiguration
import io.gatling.core.util.cache.Cache

import com.fasterxml.jackson.databind.JsonNode
import io.burt.jmespath.{ Expression, RuntimeConfiguration }
import io.burt.jmespath.jackson.JacksonRuntime
import io.burt.jmespath.function.{ FunctionRegistry, Function => JmesPathFunction }

private[gatling] object JmesPathFunctions {

  private[jmespath] var functions: Seq[JmesPathFunction] = Nil

  def register(functions: Seq[JmesPathFunction]): Unit = {
    if (functions.nonEmpty) {
      throw new UnsupportedOperationException("JmesPath functions have already been registered")
    }
    this.functions = functions
  }
}

private[gatling] class JmesPaths(implicit configuration: GatlingConfiguration) {

  private val runtime = new JacksonRuntime(
    new RuntimeConfiguration.Builder().withFunctionRegistry(FunctionRegistry.defaultRegistry.extend(JmesPathFunctions.functions: _*)).build
  )

  private val jmesPathCache = {
    def compile(expression: String): Validation[Expression[JsonNode]] =
      safely()(runtime.compile(expression).success)

    Cache.newConcurrentLoadingCache(configuration.core.extract.jsonPath.cacheMaxCapacity, compile)
  }

  def extract[X: JsonFilter](json: JsonNode, expression: String): Validation[Option[X]] =
    compileJmesPath(expression).map { compiledExpression =>
      val node = compiledExpression.search(json)
      JsonFilter[X].filter.lift(node)
    }

  private def compileJmesPath(expression: String): Validation[Expression[JsonNode]] = jmesPathCache.get(expression)
}
