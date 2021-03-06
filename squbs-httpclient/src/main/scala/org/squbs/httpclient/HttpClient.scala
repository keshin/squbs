/*
 *  Copyright 2015 PayPal
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.squbs.httpclient

import akka.actor._
import akka.pattern.{CircuitBreaker, _}
import akka.util.Timeout
import org.squbs.httpclient.Configuration._
import org.squbs.httpclient.HttpClientActorMessage.{MarkDownSuccess, MarkUpSuccess}
import org.squbs.httpclient.endpoint.EndpointRegistry
import org.squbs.httpclient.env.{Default, Environment, EnvironmentRegistry}
import org.squbs.httpclient.pipeline.HttpClientUnmarshal._
import org.squbs.pipeline.{PipelineSetting, SimplePipelineConfig}
import spray.http.{HttpResponse, Uri}
import spray.httpx.marshalling.Marshaller
import spray.httpx.unmarshalling._

import scala.collection.mutable.ListBuffer
import scala.concurrent._

object Status extends Enumeration {
  type Status = Value
  val UP, DOWN = Value
}

trait HttpClientPathBuilder {

  def buildRequestUri(path: String, paramMap: Map[String, Any] = Map.empty[String, Any]): String = {
    if (paramMap.isEmpty) {
      Uri(path).toString()
    } else {
      val trimPath =
        if (path.length > 1 && path.charAt(path.length - 1) == '/') path.substring(0, path.length - 1)
        else path
      val filteredParamMap = paramMap.filter{ pair =>
        pair._2.isInstanceOf[String] ||
        pair._2.isInstanceOf[Double] ||
        pair._2.isInstanceOf[Float]  ||
        pair._2.isInstanceOf[Int]    ||
        pair._2.isInstanceOf[Short]  ||
        pair._2.isInstanceOf[Byte]   ||
        pair._2.isInstanceOf[Char]   ||
        pair._2.isInstanceOf[Boolean]
      }.map(pair => (pair._1, pair._2.toString))
      Uri(trimPath).withQuery(filteredParamMap).toString()
    }
  }
}

object HttpClientPathBuilder extends HttpClientPathBuilder

case class HttpClient(name: String,
                      env: Environment = Default,
                      status: Status.Status = Status.UP,
                      config: Option[Configuration] = None)
                     (actorCreator: (ActorRefFactory) => Future[ActorRef] = {(arf) =>
                        import arf.dispatcher
                        Future(arf.actorOf(Props(classOf[HttpClientActor], name, env)))
                      })(implicit actorRefFactory: ActorRefFactory) { self =>

  val system = actorRefFactory match {
    case sys: ActorSystem => sys
    case ctx: ActorContext => ctx.system
    case other => throw new IllegalArgumentException(s"Cannot create HttpClient with ActorRefFactory Impl ${other.getClass}")
  }

  implicit val ec = actorRefFactory.dispatcher

  val fActorRef = actorCreator(actorRefFactory)

  val endpoint = {
    val serviceEndpoint = EndpointRegistry(system).resolve(name, env)
    serviceEndpoint match {
      case Some(se) => config match {
        case Some(conf) => se.copy(config = conf)
        case None       => se
      }
      case None     => throw HttpClientEndpointNotExistException(name, env)
    }
  }

  val cbMetrics = CircuitBreakerMetrics(CircuitBreakerStatus.Closed, 0, 0, 0, 0, ListBuffer.empty[ServiceCall])

  val cb: CircuitBreaker = {
    val cbConfig = endpoint.config.settings.circuitBreakerConfig
    new CircuitBreaker(system.scheduler, cbConfig.maxFailures, cbConfig.callTimeout, cbConfig.resetTimeout)(system.dispatcher)
  }

  cb.onClose{
    cbMetrics.status = CircuitBreakerStatus.Closed
  }

  cb.onOpen{
    cbMetrics.status = CircuitBreakerStatus.Open
  }

  cb.onHalfOpen{
    cbMetrics.status = CircuitBreakerStatus.HalfOpen
  }

  def get[R](uri: String, reqSettings: RequestSettings = defaultRequestSettings)
                                      (implicit timeout: Timeout = reqSettings.timeout, unmarshaller : FromResponseUnmarshaller[R]): Future[R] = {
    val fHttpResponse = fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Get(uri, reqSettings)).mapTo[HttpResponse]}
    unmarshall[R](fHttpResponse)
  }

  def options[R](uri: String, reqSettings: RequestSettings = defaultRequestSettings)
                                          (implicit timeout: Timeout = reqSettings.timeout, unmarshaller : FromResponseUnmarshaller[R]): Future[R] = {
    val fHttpResponse = fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Options(uri, reqSettings)).mapTo[HttpResponse]}
    unmarshall[R](fHttpResponse)
  }

  def delete[R](uri: String, reqSettings: RequestSettings = defaultRequestSettings)
                                         (implicit timeout: Timeout = reqSettings.timeout, unmarshaller : FromResponseUnmarshaller[R]): Future[R] = {
    val fHttpResponse = fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Delete(uri, reqSettings)).mapTo[HttpResponse]}
    unmarshall[R](fHttpResponse)
  }

  def post[T, R](uri: String, content: Option[T], reqSettings: RequestSettings = defaultRequestSettings)
                                                      (implicit timeout: Timeout = reqSettings.timeout, marshaller: Marshaller[T], unmarshaller : FromResponseUnmarshaller[R]): Future[R] = {
    val fHttpResponse = fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Post(uri, content, marshaller, reqSettings)).mapTo[HttpResponse]}
    unmarshall[R](fHttpResponse)
  }

  def put[T, R](uri: String, content: Option[T], reqSettings: RequestSettings = defaultRequestSettings)
                                                     (implicit timeout: Timeout = reqSettings.timeout, marshaller: Marshaller[T], unmarshaller : FromResponseUnmarshaller[R]): Future[R] = {
    val fHttpResponse = fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Put(uri, content, marshaller, reqSettings)).mapTo[HttpResponse]}
    unmarshall[R](fHttpResponse)
  }

  private def unmarshall[R](httpResponse: Future[HttpResponse])(implicit ummarshaller : FromResponseUnmarshaller[R]) : Future[R] = {
    httpResponse flatMap { response =>
      try {
        Future.fromTry(response.unmarshalTo[R])
      } catch {
        case t: Throwable => Future.failed(t)
      }
    }
  }

  def raw = new RawHttpClient(this)

  //TODO val? lazy val?
  val defaultRequestSettings = Configuration.defaultRequestSettings(endpoint.config, config)

  def withConfig(config: Configuration): HttpClient = {
    implicit val timeout = defaultFutureTimeout
    HttpClient(name, env, status, Some(config))(
      (_) => fActorRef flatMap { ref => (ref ? HttpClientActorMessage.UpdateConfig(config)).mapTo[ActorRef]}
    )
  }

  def withSettings(settings: Settings): HttpClient = {
    implicit val timeout = defaultFutureTimeout
    HttpClient(name, env, status, Some(config.getOrElse(Configuration()).copy(settings = settings)))(
      (_) => fActorRef flatMap { ref => (ref ? HttpClientActorMessage.UpdateSettings(settings)).mapTo[ActorRef]}
    )
  }

  @deprecated
  def withPipeline(pipeline: Option[SimplePipelineConfig]): HttpClient = {
    withPipelineSetting(Some(PipelineSetting(config = pipeline)))
  }

  def withPipelineSetting(pipelineSetting: Option[PipelineSetting]): HttpClient = {
    implicit val timeout = defaultFutureTimeout
    HttpClient(name, env, status, Some(config.getOrElse(Configuration()).copy(pipeline = pipelineSetting)))(
      (_) => fActorRef flatMap { ref => (ref ? HttpClientActorMessage.UpdatePipeline(pipelineSetting)).mapTo[ActorRef]}
    )
  }

  def markDown: Future[MarkDownSuccess.type] = {
    implicit val timeout = defaultFutureTimeout
    fActorRef flatMap { ref => (ref ? HttpClientActorMessage.MarkDown).mapTo[MarkDownSuccess.type]}
  }

  def markUp: Future[MarkUpSuccess.type] = {
    implicit val timeout = defaultFutureTimeout
    fActorRef flatMap { ref => (ref ? HttpClientActorMessage.MarkUp).mapTo[MarkUpSuccess.type]}
  }

  def readyFuture: Future[Unit] = {
    fActorRef map {_ => }
  }
}

class RawHttpClient(private val client : HttpClient) {
  import client._

  def get(uri: String, reqSettings: RequestSettings = defaultRequestSettings)
         (implicit timeout: Timeout = reqSettings.timeout): Future[HttpResponse] = {
    fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Get(uri, reqSettings)).mapTo[HttpResponse]}
  }

  def post[T](uri: String, content: Option[T], reqSettings: RequestSettings = defaultRequestSettings)
             (implicit timeout: Timeout = reqSettings.timeout, marshaller: Marshaller[T]): Future[HttpResponse] = {
    fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Post[T](uri, content, marshaller, reqSettings)).mapTo[HttpResponse]}
  }

  def put[T](uri: String, content: Option[T], reqSettings: RequestSettings = defaultRequestSettings)
            (implicit timeout: Timeout = reqSettings.timeout, marshaller: Marshaller[T]): Future[HttpResponse] = {
    fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Put[T](uri, content, marshaller, reqSettings)).mapTo[HttpResponse]}
  }

  def head(uri: String, reqSettings: RequestSettings = defaultRequestSettings)
          (implicit timeout: Timeout = reqSettings.timeout): Future[HttpResponse] = {
    fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Head(uri, reqSettings)).mapTo[HttpResponse]}
  }

  def delete(uri: String, reqSettings: RequestSettings = defaultRequestSettings)
            (implicit timeout: Timeout = reqSettings.timeout): Future[HttpResponse] = {
    fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Delete(uri, reqSettings)).mapTo[HttpResponse]}
  }

  def options(uri: String, reqSettings: RequestSettings = defaultRequestSettings)
             (implicit timeout: Timeout = reqSettings.timeout): Future[HttpResponse] = {
    fActorRef flatMap { ref => (ref ? HttpClientActorMessage.Options(uri, reqSettings)).mapTo[HttpResponse]}
  }
}

object HttpClientFactory {

  def get(name: String, env: Environment = Default)(implicit system: ActorSystem): HttpClient = {
    val newEnv = env match {
      case Default => EnvironmentRegistry(system).resolve(name)
      case _ => env
    }
    implicit val timeout = defaultFutureTimeout
    HttpClient(name, newEnv, Status.UP, None)(
    (_) => (HttpClientManager(system).httpClientManager ? HttpClientManagerMessage.Get(name, newEnv)).mapTo[ActorRef]
    )
  }
}