package config

import akka.actor.ActorSystem
import com.github.scribejava.apis.TwitterApi
import com.github.scribejava.core.builder.ServiceBuilder
import com.github.scribejava.core.model.OAuth1AccessToken
import com.github.scribejava.core.oauth.OAuth10aService
import com.typesafe.config.Config
import grizzled.slf4j.Logging
import models._
import org.bitcoins.commons.config._
import org.bitcoins.commons.util.NativeProcessFactory
import org.bitcoins.core.hd.HDPurposes
import org.bitcoins.core.wallet.keymanagement.KeyManagerParams
import org.bitcoins.crypto.{AesPassword, SchnorrPublicKey}
import org.bitcoins.db._
import org.bitcoins.keymanager.WalletStorage
import org.bitcoins.keymanager.bip39.BIP39KeyManager
import org.bitcoins.keymanager.config.KeyManagerAppConfig
import org.bitcoins.lnd.rpc.LndRpcClient
import org.bitcoins.lnd.rpc.config._
import org.bitcoins.rpc.client.v24.BitcoindV24RpcClient
import org.bitcoins.rpc.config.BitcoindAuthCredentials.PasswordBased
import org.bitcoins.rpc.config.BitcoindInstanceRemote
import org.scalastr.core.NostrPrivateKey
import scodec.bits.ByteVector

import java.io.File
import java.net.URI
import java.nio.file.{Files, Path, Paths}
import scala.concurrent._
import scala.jdk.CollectionConverters._
import scala.util.{Properties, Try}

/** Configuration for the Bitcoin-S wallet
  *
  * @param directory
  *   The data directory of the wallet
  * @param configOverrides
  *   Optional sequence of configuration overrides
  */
case class OpReturnBotAppConfig(
    private val directory: Path,
    override val configOverrides: Vector[Config])(implicit system: ActorSystem)
    extends DbAppConfig
    with JdbcProfileComponent[OpReturnBotAppConfig]
    with DbManagement
    with Logging {
  implicit val ec: ExecutionContextExecutor = system.dispatcher
  override val moduleName: String = OpReturnBotAppConfig.moduleName
  override type ConfigType = OpReturnBotAppConfig

  override val appConfig: OpReturnBotAppConfig = this

  import profile.api._

  override def newConfigOfType(configs: Vector[Config]): OpReturnBotAppConfig =
    OpReturnBotAppConfig(directory, configs)

  val baseDatadir: Path = directory

  private lazy val lndDataDir: Path = {
    config.getStringOrNone(s"bitcoin-s.lnd.datadir") match {
      case Some(str) => Paths.get(str.replace("~", Properties.userHome))
      case None      => LndInstanceLocal.DEFAULT_DATADIR
    }
  }

  private lazy val lndRpcUri: Option[URI] = {
    config.getStringOrNone(s"bitcoin-s.lnd.rpcUri").map { str =>
      if (str.startsWith("http") || str.startsWith("https")) {
        new URI(str)
      } else {
        new URI(s"http://$str")
      }
    }
  }

  private lazy val lndMacaroonOpt: Option[String] = {
    config.getStringOrNone(s"bitcoin-s.lnd.macaroonFile").map { pathStr =>
      val path = Paths.get(pathStr.replace("~", Properties.userHome))
      val bytes = Files.readAllBytes(path)

      ByteVector(bytes).toHex
    }
  }

  private lazy val lndTlsCertOpt: Option[File] = {
    config.getStringOrNone(s"bitcoin-s.lnd.tlsCert").map { pathStr =>
      val path = Paths.get(pathStr.replace("~", Properties.userHome))
      path.toFile
    }
  }

  private lazy val lndBinary: File = {
    config.getStringOrNone(s"bitcoin-s.lnd.binary") match {
      case Some(str) => new File(str.replace("~", Properties.userHome))
      case None =>
        NativeProcessFactory
          .findExecutableOnPath("lnd")
          .getOrElse(sys.error("Could not find lnd binary"))
    }
  }

  private lazy val lndInstance: LndInstance = {
    lndMacaroonOpt match {
      case Some(value) =>
        LndInstanceRemote(
          rpcUri = lndRpcUri.getOrElse(new URI("http://127.0.0.1:10009")),
          macaroon = value,
          certFileOpt = lndTlsCertOpt,
          certificateOpt = None)
      case None =>
        val dir = lndDataDir.toFile
        require(dir.exists, s"$lndDataDir does not exist!")
        require(dir.isDirectory, s"$lndDataDir is not a directory!")

        val confFile = lndDataDir.resolve("lnd.conf").toFile
        val config = LndConfig(confFile, dir)

        val remoteConfig = config.lndInstanceRemote

        lndRpcUri match {
          case Some(uri) => remoteConfig.copy(rpcUri = uri)
          case None      => remoteConfig
        }
    }
  }

  lazy val lndRpcClient: LndRpcClient =
    new LndRpcClient(lndInstance, Try(lndBinary).toOption)

  private lazy val bitcoindUri: String = {
    config.getString(s"bitcoin-s.bitcoind.uri")
  }

  private lazy val bitcoindRpcUri: String = {
    config.getString(s"bitcoin-s.bitcoind.rpcUri")
  }

  private lazy val bitcoindRpcUser: String = {
    config.getString(s"bitcoin-s.bitcoind.auth.user")
  }

  private lazy val bitcoindRpcPassword: String = {
    config.getString(s"bitcoin-s.bitcoind.auth.password")
  }

  private lazy val bitcoindInstance: BitcoindInstanceRemote = {
    BitcoindInstanceRemote(network,
                           new URI(bitcoindUri),
                           new URI(bitcoindRpcUri),
                           PasswordBased(
                             bitcoindRpcUser,
                             bitcoindRpcPassword
                           ))
  }

  lazy val bitcoindClient: BitcoindV24RpcClient =
    new BitcoindV24RpcClient(bitcoindInstance)

  lazy val telegramCreds: String =
    config.getStringOrElse(s"bitcoin-s.$moduleName.telegramCreds", "")

  lazy val telegramId: String =
    config.getStringOrElse(s"bitcoin-s.$moduleName.telegramId", "")

  lazy val bannedWords: Vector[String] = Try {
    val list = config.getStringList(s"twitter.banned-words")
    list.asScala.toVector
  }.getOrElse(Vector.empty)

  def censorMessage(
      message: String,
      bannedWords: Vector[String] = bannedWords): String = {
    bannedWords.foldLeft(message)((acc, word) => acc.replaceAll(word, "*****"))
  }

  lazy val twitterClientId: String = config.getString(s"twitter.clientid")

  lazy val twitterClientSecret: String =
    config.getString(s"twitter.clientsecret")

  lazy val twitterAccessToken = new OAuth1AccessToken(
    config.getString(s"twitter.access.token"),
    config.getString(s"twitter.access.secret")
  )

  lazy val twitterClient: OAuth10aService = {
    new ServiceBuilder(twitterClientId)
      .apiSecret(twitterClientSecret)
      .build(TwitterApi.instance())
  }

  lazy val kmConf: KeyManagerAppConfig =
    KeyManagerAppConfig(baseDatadir, configOverrides)

  lazy val seedPath: Path = {
    kmConf.seedFolder.resolve("encrypted-bitcoin-s-seed.json")
  }

  lazy val kmParams: KeyManagerParams =
    KeyManagerParams(seedPath, HDPurposes.SegWit, network)

  lazy val aesPasswordOpt: Option[AesPassword] = kmConf.aesPasswordOpt
  lazy val bip39PasswordOpt: Option[String] = kmConf.bip39PasswordOpt

  lazy val extraNostrPrivKey: Option[NostrPrivateKey] = {
    if (config.hasPath("nostr.extraNostrPrivKey")) {
      val privKey = config.getString("nostr.extraNostrPrivKey")
      Some(NostrPrivateKey.fromString(privKey))
    } else None
  }

  lazy val extraNostrPubKey: Option[SchnorrPublicKey] =
    extraNostrPrivKey.map(_.key.schnorrPublicKey)

  lazy val nostrRelays: Vector[String] = {
    if (config.hasPath("nostr.relays")) {
      config.getStringList(s"nostr.relays").asScala.toVector
    } else Vector.empty
  }

  lazy val writeOnlyRelays: Vector[String] = {
    if (config.hasPath("nostr.writeOnlyRelays")) {
      config.getStringList(s"nostr.writeOnlyRelays").asScala.toVector
    } else Vector.empty
  }

  lazy val allRelays: Vector[String] =
    nostrRelays ++ writeOnlyRelays

  def seedExists(): Boolean = {
    WalletStorage.seedExists(seedPath)
  }

  def initialize(): Unit = {
    // initialize seed
    if (!seedExists()) {
      BIP39KeyManager.initialize(aesPasswordOpt = aesPasswordOpt,
                                 kmParams = kmParams,
                                 bip39PasswordOpt = bip39PasswordOpt) match {
        case Left(err) => sys.error(err.toString)
        case Right(_) =>
          logger.info("Successfully generated a seed and key manager")
      }
    }

    ()
  }

  override def start(): Future[Unit] = {
    logger.info(s"Initializing setup")

    if (Files.notExists(baseDatadir)) {
      Files.createDirectories(baseDatadir)
    }

    val numMigrations = migrate().migrationsExecuted
    logger.info(s"Applied $numMigrations")

    initialize()
    Future.unit
  }

  override def stop(): Future[Unit] = Future.unit

  override lazy val dbPath: Path = baseDatadir

  override val allTables: List[TableQuery[Table[_]]] =
    List(OpReturnRequestDAO()(ec, this).table)
}

object OpReturnBotAppConfig
    extends AppConfigFactoryBase[OpReturnBotAppConfig, ActorSystem] {

  val DEFAULT_DATADIR: Path = Paths.get(Properties.userHome, ".op-return-bot")

  override def fromDefaultDatadir(confs: Vector[Config] = Vector.empty)(implicit
      ec: ActorSystem): OpReturnBotAppConfig = {
    fromDatadir(DEFAULT_DATADIR, confs)
  }

  override def fromDatadir(datadir: Path, confs: Vector[Config])(implicit
      ec: ActorSystem): OpReturnBotAppConfig =
    OpReturnBotAppConfig(datadir, confs)

  override val moduleName: String = "opreturnbot"
}
