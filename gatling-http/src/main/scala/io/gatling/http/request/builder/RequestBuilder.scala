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

package io.gatling.http.request.builder

import io.gatling.commons.validation._
import io.gatling.core.check.Validator
import io.gatling.core.session._
import io.gatling.core.session.el.El
import io.gatling.http.check.status.HttpStatusCheckBuilder._
import io.gatling.http.util.HttpHelper._
import io.gatling.http.{ HeaderNames, HeaderValues }
import io.gatling.http.check.HttpCheck
import io.gatling.http.check.status.HttpStatusCheckMaterializer
import io.gatling.http.client.SignatureCalculator
import io.gatling.http.client.oauth.{ ConsumerKey, RequestToken }
import io.gatling.http.client.proxy.ProxyServer
import io.gatling.http.client.realm.Realm
import io.gatling.http.client.uri.Uri
import io.gatling.http.client.sign.OAuthSignatureCalculator
import io.gatling.http.protocol.Proxy
import io.gatling.http.util.HttpHelper

import com.softwaremill.quicklens._
import io.netty.handler.codec.http.HttpMethod

final case class CommonAttributes(
    requestName:         Expression[String],
    method:              HttpMethod,
    urlOrURI:            Either[Expression[String], Uri],
    disableUrlEncoding:  Option[Boolean]                         = None,
    queryParams:         List[HttpParam]                         = Nil,
    headers:             Map[String, Expression[String]]         = Map.empty,
    realm:               Option[Expression[Realm]]               = None,
    virtualHost:         Option[Expression[String]]              = None,
    proxy:               Option[ProxyServer]                     = None,
    signatureCalculator: Option[Expression[SignatureCalculator]] = None
)

object RequestBuilder {

  /**
   * This is the default HTTP check used to verify that the response status is 2XX
   */
  val DefaultHttpCheck: HttpCheck = {
    val okStatusValidator: Validator[Int] = new Validator[Int] {
      override val name: String = OkCodes.mkString("in(", ",", ")")
      override def apply(actual: Option[Int], displayActualValue: Boolean): Validation[Option[Int]] = actual match {
        case Some(actualValue) =>
          if (HttpHelper.isOk(actualValue))
            actual.success
          else
            s"found $actualValue".failure
        case _ => Validator.FoundNothingFailure
      }
    }

    Status.find.validate(okStatusValidator.expressionSuccess).build(HttpStatusCheckMaterializer)
  }

  private val JsonHeaderValueExpression = HeaderValues.ApplicationJson.expressionSuccess
  private val XmlHeaderValueExpression = HeaderValues.ApplicationXml.expressionSuccess
  val AcceptAllHeaderValueExpression: Expression[String] = "*/*".expressionSuccess
  val AcceptCssHeaderValueExpression: Expression[String] = "text/css,*/*;q=0.1".expressionSuccess

  def oauth1SignatureCalculator(
    consumerKey:        Expression[String],
    clientSharedSecret: Expression[String],
    token:              Expression[String],
    tokenSecret:        Expression[String]
  ): Expression[SignatureCalculator] = session =>
    for {
      ck <- consumerKey(session)
      css <- clientSharedSecret(session)
      tk <- token(session)
      tks <- tokenSecret(session)
    } yield new OAuthSignatureCalculator(new ConsumerKey(ck, css), new RequestToken(tk, tks))
}

abstract class RequestBuilder[B <: RequestBuilder[B]] {

  def commonAttributes: CommonAttributes

  private[http] def newInstance(commonAttributes: CommonAttributes): B

  def queryParam(key: Expression[String], value: Expression[Any]): B = queryParam(SimpleParam(key, value))
  def multivaluedQueryParam(key: Expression[String], values: Expression[Seq[Any]]): B = queryParam(MultivaluedParam(key, values))

  def queryParamSeq(seq: Seq[(String, Any)]): B = queryParamSeq(seq2SeqExpression(seq))
  def queryParamSeq(seq: Expression[Seq[(String, Any)]]): B = queryParam(ParamSeq(seq))

  def queryParamMap(map: Map[String, Any]): B = queryParamSeq(map2SeqExpression(map))
  def queryParamMap(map: Expression[Map[String, Any]]): B = queryParam(ParamMap(map))

  private def queryParam(param: HttpParam): B = newInstance(modify(commonAttributes)(_.queryParams).using(_ ::: List(param)))

  /**
   * Adds a header to the request
   *
   * @param name the name of the header
   * @param value the value of the header
   */
  def header(name: String, value: Expression[String]): B = newInstance(modify(commonAttributes)(_.headers).using(_ + (name -> value)))

  /**
   * Adds several headers to the request at the same time
   *
   * @param newHeaders a scala map containing the headers to add
   */
  def headers(newHeaders: Map[String, String]): B = newInstance(modify(commonAttributes)(_.headers).using(_ ++ newHeaders.mapValues(_.el[String])))

  /**
   * Adds Accept and Content-Type headers to the request set with "application/json" values
   */
  def asJson: B = header(HeaderNames.Accept, RequestBuilder.JsonHeaderValueExpression).header(HeaderNames.ContentType, RequestBuilder.JsonHeaderValueExpression)

  /**
   * Adds Accept and Content-Type headers to the request set with "application/xml" values
   */
  def asXml: B = header(HeaderNames.Accept, RequestBuilder.XmlHeaderValueExpression).header(HeaderNames.ContentType, RequestBuilder.XmlHeaderValueExpression)

  /**
   * Adds BASIC authentication to the request
   *
   * @param username the username needed
   * @param password the password needed
   */
  def basicAuth(username: Expression[String], password: Expression[String]): B = authRealm(HttpHelper.buildBasicAuthRealm(username, password))
  def digestAuth(username: Expression[String], password: Expression[String]): B = authRealm(HttpHelper.buildDigestAuthRealm(username, password))
  private def authRealm(realm: Expression[Realm]): B = newInstance(modify(commonAttributes)(_.realm).setTo(Some(realm)))

  /**
   * @param virtualHost a virtual host to override default compute one
   */
  def virtualHost(virtualHost: Expression[String]): B = newInstance(modify(commonAttributes)(_.virtualHost).setTo(Some(virtualHost)))

  def disableUrlEncoding: B = newInstance(modify(commonAttributes)(_.disableUrlEncoding).setTo(Some(true)))

  def proxy(httpProxy: Proxy): B = newInstance(modify(commonAttributes)(_.proxy).setTo(Some(httpProxy.proxyServer)))

  def sign(calculator: Expression[SignatureCalculator]): B = newInstance(modify(commonAttributes)(_.signatureCalculator).setTo(Some(calculator)))

  def signWithOAuth1(consumerKey: Expression[String], clientSharedSecret: Expression[String], token: Expression[String], tokenSecret: Expression[String]): B =
    sign(RequestBuilder.oauth1SignatureCalculator(consumerKey, clientSharedSecret, token, tokenSecret))
}
