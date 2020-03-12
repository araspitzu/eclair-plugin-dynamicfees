/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.sql.{Connection, DriverManager}
import java.util.concurrent.atomic.AtomicLong

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, NodeParams, ShortChannelId, ToMilliSatoshiConversion, UInt64, feerateKw2KB, randomBytes32, randomKey, wire}
import fr.acinq.bitcoin.{Block, Btc, ByteVector32, DeterministicWallet, Satoshi, Script, Transaction}
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair.blockchain.{EclairWallet, MakeFundingTxResponse}
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeTargets, FeeratesPerKw, OnChainFeeConf}
import fr.acinq.eclair.channel.Helpers.Funding
import fr.acinq.eclair.channel.{Channel, ChannelVersion, Commitments, DATA_NORMAL, LocalChanges, LocalCommit, LocalParams, PublishableTxs, RemoteChanges, RemoteCommit, RemoteParams}
import fr.acinq.eclair.crypto.{LocalKeyManager, ShaChain}
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.router.{Announcements, RouterConf}
import fr.acinq.eclair.transactions.Transactions.CommitTx
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc}
import fr.acinq.eclair.wire.{Color, EncodingType, NodeAddress}
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.Future
import scala.concurrent.duration._

object TestConstants {
  val seed = ByteVector32(ByteVector.fill(32)(1))
  val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)
  val defaultBlockHeight = 400000
  val fundingSatoshis = 1000000L sat
  val pushMsat = 200000000L msat
  val feeratePerKw = 10000L
  val emptyOnionPacket = wire.OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(1300)(0), ByteVector32.Zeroes)

  class TestFeeEstimator extends FeeEstimator {
    private var currentFeerates = FeeratesPerKw.single(feeratePerKw)

    override def getFeeratePerKb(target: Int): Long = feerateKw2KB(currentFeerates.feePerBlock(target))

    override def getFeeratePerKw(target: Int): Long = currentFeerates.feePerBlock(target)

    def setFeerate(feeratesPerKw: FeeratesPerKw): Unit = {
      currentFeerates = feeratesPerKw
    }
  }

  class TestWallet extends EclairWallet {
    override def getBalance: Future[Satoshi] = ???
    override def getFinalAddress: Future[String] = ???
    override def makeFundingTx(pubkeyScript: ByteVector, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] = ???
    override def commit(tx: Transaction): Future[Boolean] = ???
    override def rollback(tx: Transaction): Future[Boolean] = ???
    override def doubleSpent(tx: Transaction): Future[Boolean] = ???
  }

  def sqliteInMemory() = DriverManager.getConnection("jdbc:sqlite::memory:")

  def inMemoryDb(connection: Connection = sqliteInMemory()): Databases = Databases.databaseByConnections(connection, connection, connection)

  object Alice {
    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams = NodeParams(
      keyManager = keyManager,
      blockCount = new AtomicLong(defaultBlockHeight),
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      features = ByteVector.fromValidHex("0a8a"),
      overrideFeatures = Map.empty,
      syncWhitelist = Set.empty,
      dustLimit = 1100 sat,
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(6, 2, 2, 6),
        feeEstimator = new TestFeeEstimator,
        maxFeerateMismatch = 1.5,
        closeOnOfflineMismatch = true,
        updateFeeMinDiffRatio = 0.1
      ),
      maxHtlcValueInFlightMsat = UInt64(150000000),
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
      htlcMinimum = 0 msat,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = CltvExpiryDelta(144),
      maxToLocalDelayBlocks = CltvExpiryDelta(1000),
      feeBase = 546000 msat,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory()),
      revocationTimeout = 20 seconds,
      pingInterval = 30 seconds,
      pingTimeout = 10 seconds,
      pingDisconnect = true,
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      multiPartPaymentExpiry = 30 seconds,
      minFundingSatoshis = 1000 sat,
      maxFundingSatoshis = 10000 sat,
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        networkStatsRefreshInterval = 1 hour,
        requestNodeAnnouncements = true,
        encodingType = EncodingType.COMPRESSED_ZLIB,
        channelRangeChunkSize = 20,
        channelQueryChunkSize = 5,
        searchMaxFeeBase = 21 sat,
        searchMaxFeePct = 0.03,
        searchMaxCltv = CltvExpiryDelta(2016),
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      enableTrampolinePayment = true
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32).publicKey)),
      isFunder = true,
      fundingSatoshis).copy(
      channelReserve = 10000 sat // Bob will need to keep that much satoshis as direct payment
    )
  }

  val localParams = LocalParams(
    keyManager.nodeId,
    fundingKeyPath = DeterministicWallet.KeyPath(Seq(42L)),
    dustLimit = Satoshi(546),
    maxHtlcValueInFlightMsat = UInt64(50000000),
    channelReserve = 1 sat,
    htlcMinimum = 10000 msat,
    toSelfDelay = CltvExpiryDelta(144),
    maxAcceptedHtlcs = 50,
    defaultFinalScriptPubKey = ByteVector.empty,
    isFunder = true,
    features = hex"deadbeef")

  val remoteParams = RemoteParams(
    nodeId = randomKey.publicKey,
    dustLimit = 546 sat,
    maxHtlcValueInFlightMsat = UInt64(5000000),
    channelReserve = 1 sat,
    htlcMinimum = 5000 msat,
    toSelfDelay = CltvExpiryDelta(144),
    maxAcceptedHtlcs = 50,
    fundingPubKey = PrivateKey(ByteVector32(ByteVector.fill(32)(1)) :+ 1.toByte).publicKey,
    revocationBasepoint = PrivateKey(ByteVector.fill(32)(2)).publicKey,
    paymentBasepoint = PrivateKey(ByteVector.fill(32)(3)).publicKey,
    delayedPaymentBasepoint = PrivateKey(ByteVector.fill(32)(4)).publicKey,
    htlcBasepoint = PrivateKey(ByteVector.fill(32)(6)).publicKey,
    features = hex"deadbeef")


  def makeChannelDataNormal(toLocal: MilliSatoshi, toRemote: MilliSatoshi, channelId: ByteVector32, shortId: ShortChannelId, chUpdateFeeProp: Long): DATA_NORMAL = {
    val channelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey, shortId, CltvExpiryDelta(42), 15 msat, 575 msat, chUpdateFeeProp, Channel.MAX_FUNDING.toMilliSatoshi)
    val fundingTx = Transaction.read("0200000001adbb20ea41a8423ea937e76e8151636bf6093b70eaff942930d20576600521fd000000006b48304502210090587b6201e166ad6af0227d3036a9454223d49a1f11839c1a362184340ef0240220577f7cd5cca78719405cbf1de7414ac027f0239ef6e214c90fcaab0454d84b3b012103535b32d5eb0a6ed0982a0479bbadc9868d9836f6ba94dd5a63be16d875069184ffffffff028096980000000000220020c015c4a6be010e21657068fc2e6a9d02b27ebe4d490a25846f7237f104d1a3cd20256d29010000001600143ca33c2e4446f4a305f23c80df8ad1afdcf652f900000000")
    val fundingAmount = fundingTx.txOut.head.amount
    val commitmentInput = Funding.makeFundingInputInfo(fundingTx.hash, 0, fundingAmount, keyManager.fundingPublicKey(localParams.fundingKeyPath).publicKey, remoteParams.fundingPubKey)

    val localCommit = LocalCommit(0, CommitmentSpec(Set.empty, 0, toLocal = toLocal, toRemote = toRemote), PublishableTxs(CommitTx(commitmentInput, Transaction(2, Nil, Nil, 0)), Nil))
    val remoteCommit = RemoteCommit(0, CommitmentSpec(Set.empty, 0, toLocal = toRemote, toRemote = toLocal), ByteVector32(hex"0303030303030303030303030303030303030303030303030303030303030303"), PrivateKey(ByteVector.fill(32)(4)).publicKey)
    val commitments = Commitments(ChannelVersion.STANDARD, localParams, remoteParams, channelFlags = 0x01.toByte, localCommit, remoteCommit, LocalChanges(Nil, Nil, Nil), RemoteChanges(Nil, Nil, Nil),
      localNextHtlcId = 32L,
      remoteNextHtlcId = 4L,
      originChannels = Map.empty,
      remoteNextCommitInfo = Right(randomKey.publicKey),
      commitInput = commitmentInput,
      remotePerCommitmentSecrets = ShaChain.init,
      channelId = channelId)

    DATA_NORMAL(commitments, shortId, buried = true, None, channelUpdate, None, None)
  }

}
