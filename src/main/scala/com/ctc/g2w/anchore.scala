package com.ctc.g2w

import com.ctc.g2w.anchore.api.PolicyBundleRecord
import requests.RequestBlob
import spray.json.{DefaultJsonProtocol, RootJsonFormat, _}
import zio._

import java.time.Instant

object anchore {
  type HttpConfig = zio.config.ZConfig[anchore.config.Http]

  type AnchoreAPI = Has[AnchoreAPI.Service]
  object AnchoreAPI {
    trait Service {
      def health(): UIO[anchore.api.Health]
      def activePolicy(): ZIO[AnchoreAuth, Throwable, PolicyBundleRecord]
      def policies(): ZIO[AnchoreAuth, Throwable, List[PolicyBundleRecord]]
    }

    def live(cfg: anchore.config.Http): Layer[AnchoreAuth, AnchoreAPI] = ZLayer.succeed(
      new AnchoreAPI.Service {
        def health(): UIO[api.Health] =
          ZIO
            .effect(requests.get(s"${cfg.url()}/health").statusCode)
            .map {
              case 200 => anchore.api.Health.Ok
            }
            .orElse(ZIO.succeed(anchore.api.Health.Fail))

        def activePolicy(): ZIO[AnchoreAuth, Throwable, PolicyBundleRecord] =
          policies().map(_.filter(_.active.getOrElse(false))).map(_.head)

        def policies(): ZIO[AnchoreAuth, Throwable, List[PolicyBundleRecord]] =
          for {
            tok <- AnchoreAuth.token()
            res = requests
              .get(s"${cfg.url()}/policies", headers = tok.headers, params = Map("detail" -> "true"))
              .text()
              .parseJson
              .convertTo[List[PolicyBundleRecord]]
          } yield res
      }
    )

    def health(): URIO[AnchoreAPI, anchore.api.Health] = ZIO.accessM(_.get.health())
    def activePolicy(): ZIO[AnchoreAPI with AnchoreAuth, Throwable, PolicyBundleRecord] =
      ZIO.accessM(_.get.activePolicy())
    def policies(): ZIO[AnchoreAPI with AnchoreAuth, Throwable, List[PolicyBundleRecord]] =
      ZIO.accessM(_.get.policies())
  }

  type AnchoreAuth = Has[AnchoreAuth.Service]
  object AnchoreAuth {
    trait Service {
      def token(): Task[OAuthToken]
    }

    def make(cfg: anchore.config.Http): Layer[Nothing, AnchoreAuth] =
      Ref
        .make(OAuthToken.Expired)
        .map { tokenRef =>
          new AnchoreAuth.Service {
            val tokenBuffer = 100 // todo;; move to config
            def token(): Task[OAuthToken] =
              tokenRef.get.flatMap { tok =>
                if (tok.expiresWithin(tokenBuffer)) {
                  ZIO
                    .effect(
                      requests
                        .post(s"${cfg.url()}/oauth/token", data = anchore.defaultAuth)
                        .text()
                        .parseJson
                        .convertTo[OAuthResponse]
                    )
                    .flatMap(r => tokenRef.updateAndGet(_ => r.token))
                } else Task.succeed(tok)
              }
          }
        }
        .toLayer

    def token(): RIO[AnchoreAuth, OAuthToken] = ZIO.accessM(_.get.token())
  }

  // grant_type=password&client_id=anonymous&username=admin&password=foobar
  val defaultAuth = OAuthCredentials("anonymous", "admin", "foobar")

  case class OAuthCredentials(client_id: String, username: String, password: String)
  object OAuthCredentials {
    implicit def oauthRequestBlob(oa: OAuthCredentials): RequestBlob = RequestBlob.FormEncodedRequestBlob(
      Seq("client_id" -> oa.client_id, "username" -> oa.username, "password" -> oa.password, "grant_type" -> "password")
    )
  }

  case class OAuthToken(value: String, expires: Instant)
  object OAuthToken {
    val Expired = OAuthToken("_", Instant.ofEpochSecond(0))

    implicit class exOauthToken(t: OAuthToken) {
      def expiresWithin(sec: Int): Boolean = t.expires.plusSeconds(sec).isAfter(t.expires)
      def isExpired: Boolean = Instant.now().isAfter(t.expires)
      def headers = Map("Authorization" -> s"Bearer ${t.value}")
    }
  }
  case class OAuthResponse(access_token: String, expires_in: Int)
  object OAuthResponse extends DefaultJsonProtocol {
    implicit val format: RootJsonFormat[OAuthResponse] = jsonFormat2(OAuthResponse.apply)

    implicit class RichResponse(r: OAuthResponse) {
      def token: OAuthToken = OAuthToken(r.access_token, Instant.now().plusSeconds(r.expires_in))
    }
  }

  object config {
    import zio.config._
    import ConfigDescriptor._

    case class Http(addr: String, port: Int) {
      def url(scheme: String = "http"): String = s"$scheme://$addr:$port"
    }
    val http: ConfigDescriptor[Http] =
      (
        string("ANCHORE_ADDR").default("localhost") |@|
          int("ANCHORE_PORT").default(8228)
      )(Http.apply, Http.unapply)
  }

  object api {
    sealed trait Health
    object Health {
      object Ok extends Health {
        override def toString: String = "Ok"
      }
      object Fail extends Health {
        override def toString: String = "Fail"
      }
    }
    case class PolicyBundle(
        id: String,
        name: Option[String],
        comment: Option[String],
        version: String,
        whitelists: Option[List[Whitelist]],
        policies: List[Policy],
        mappings: List[MappingRule],
        whitelisted_images: Option[List[ImageSelectionRule]],
        blacklisted_images: Option[List[ImageSelectionRule]]
    )
    object PolicyBundle extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[PolicyBundle] = jsonFormat9(PolicyBundle.apply)
    }

    case class PolicyBundleRecord(
        policyId: Option[String],
        active: Option[Boolean],
        userId: Option[String],
        policy_source: Option[String],
        policybundle: Option[PolicyBundle]
    )
    object PolicyBundleRecord extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[PolicyBundleRecord] = jsonFormat5(PolicyBundleRecord.apply)
    }

    case class Policy(
        id: String,
        name: Option[String],
        comment: Option[String],
        version: String,
        rules: Option[List[PolicyRule]]
    )
    object Policy extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[Policy] = jsonFormat5(Policy.apply)
    }

    case class PolicyRule(id: Option[String], gate: String, trigger: String, action: String)
    object PolicyRule extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[PolicyRule] = jsonFormat4(PolicyRule.apply)
    }

    case class Whitelist(
        id: String,
        name: Option[String],
        version: String,
        comment: Option[String],
        items: Option[List[WhitelistItem]]
    )
    object Whitelist extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[Whitelist] = jsonFormat5(Whitelist.apply)
    }

    case class WhitelistItem(id: Option[String], gate: String, trigger_id: String)
    object WhitelistItem extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[WhitelistItem] = jsonFormat3(WhitelistItem.apply)
    }

    case class MappingRule(
        id: Option[String],
        name: String,
        whitelist_ids: Option[List[String]],
        policy_id: Option[String],
        policy_ids: Option[List[String]],
        registry: String,
        repository: String,
        image: ImageRef
    )
    object MappingRule extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[MappingRule] = jsonFormat8(MappingRule.apply)
    }

    case class ImageSelectionRule(
        id: Option[String],
        name: String,
        registry: String,
        repository: String,
        image: ImageRef
    )
    object ImageSelectionRule extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[ImageSelectionRule] = jsonFormat5(ImageSelectionRule.apply)
    }

    case class ImageRef(`type`: String, value: String)
    object ImageRef extends DefaultJsonProtocol {
      implicit val format: RootJsonFormat[ImageRef] = jsonFormat2(ImageRef.apply)
    }
  }
}
