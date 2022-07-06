package com.application.passportreaderk

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.application.passportreaderkotlin.model.ResultModel
import com.application.passportreaderkotlin.utils.ImageUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.sf.scuba.smartcards.CardService
import org.apache.commons.io.IOUtils
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.x509.Certificate
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.*
import org.jmrtd.lds.iso19794.FaceImageInfo
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.*


open class MainActivity : AppCompatActivity() {

    val TAG = MainActivity::class.java.simpleName

    private lateinit var scanNfcButton: Button

    private var mrzInfo: MRZInfo? = null
    private var pendingIntent: PendingIntent? = null


    var documentNumber: String = "P03734357"
    var dateOfBirth: String = "001121"
    var dateOfExpiry: String = "220221"

    internal var mHandler = Handler(Looper.getMainLooper())
    var progressBar: ProgressBar? = null

    var resultModel = ResultModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scanNfcButton = findViewById(R.id.scan_nfc)
        progressBar = findViewById(R.id.progressBar)

        scanNfcButton.setOnClickListener {
            scanNfcFunction()
        }

    }


    private fun scanNfcFunction() {
        // Setup Pending Intent


        val intent = Intent(this, javaClass).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE
        )

        //Trigger NFC Chip
        onEnableNfc()
    }

    private fun onEnableNfc() {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter != null) {
            val filter = arrayOf(arrayOf("android.nfc.tech.IsoDep"))
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, filter)
        }
    }

    public override fun onNewIntent(intent: Intent) {
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action || NfcAdapter.ACTION_TECH_DISCOVERED == intent.action) {
            val tag = intent.extras!!.getParcelable<Tag>(NfcAdapter.EXTRA_TAG)
            if (listOf(*tag!!.techList).contains("android.nfc.tech.IsoDep")) {
                onTagReceived(tag)
            }
        } else {
            super.onNewIntent(intent)
        }
    }



    private fun onTagReceived(tag: Tag) {
        mHandler.post { progressBar!!.visibility = View.VISIBLE }

        val bacKey: BACKeySpec = BACKey(documentNumber, dateOfBirth, dateOfExpiry)
        val intent = Intent(this, ResultActivity::class.java)

        resultModel = ResultModel()
        GlobalScope.launch {
            readNFCBac(IsoDep.get(tag), bacKey, this)

            /// Result

            mHandler.post { progressBar!!.visibility = View.GONE }

            val mrzInfo = resultModel.dg1File!!.mrzInfo
            intent.putExtra(
                ResultActivity.KEY_FIRST_NAME,
                mrzInfo.secondaryIdentifier.replace("<", " ")
            )
            intent.putExtra(
                ResultActivity.KEY_LAST_NAME,
                mrzInfo.primaryIdentifier.replace("<", " ")
            )
            intent.putExtra(ResultActivity.KEY_GENDER, mrzInfo.gender.toString())
            intent.putExtra(ResultActivity.KEY_STATE, mrzInfo.issuingState)
            intent.putExtra(ResultActivity.KEY_NATIONALITY, mrzInfo.nationality)
            var passiveAuthStr: String? = ""
            if (resultModel.passiveAuthSuccess) {
                passiveAuthStr = "Pass"
            } else {
                passiveAuthStr = "Failed"
            }
            var chipAuthStr: String? = ""
            if (resultModel.chipAuthSucceeded) {
                chipAuthStr = "Pass"
            } else {
                chipAuthStr = "Failed"
            }
            intent.putExtra(ResultActivity.KEY_PASSIVE_AUTH, passiveAuthStr)
            intent.putExtra(ResultActivity.KEY_CHIP_AUTH, chipAuthStr)
            if (resultModel.bitmap != null) {
                if (false) {
                    intent.putExtra(ResultActivity.KEY_PHOTO_BASE64, resultModel.imageBase64)
                } else {
                    val ratio = 320.0 / resultModel.bitmap!!.height
                    val targetHeight = (resultModel.bitmap!!.height * ratio).toInt()
                    val targetWidth = (resultModel.bitmap!!.width * ratio).toInt()
                    intent.putExtra(
                        ResultActivity.KEY_PHOTO,
                        Bitmap.createScaledBitmap(
                            resultModel.bitmap!!,
                            targetWidth,
                            targetHeight,
                            false
                        )
                    )
                }
            }
            startActivity(intent)
        }
    }


    private fun readNFCBac(
        isoDep: IsoDep,
        bacKey: BACKeySpec,
        context: CoroutineScope
    ) {

        try {
            val cardService = CardService.getInstance(isoDep)
            cardService.open()
            val service = PassportService(
                cardService,
                PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
                PassportService.DEFAULT_MAX_BLOCKSIZE,
                false,
                false
            )
            service.open()
            var paceSucceeded = false
           /* try {
                val cardAccessFile =
                    CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS))
                val securityInfoCollection = cardAccessFile.securityInfos
                for (securityInfo in securityInfoCollection) {
                    if (securityInfo is PACEInfo) {
                        val paceInfo = securityInfo
                        service.doPACE(
                            bacKey,
                            paceInfo.objectIdentifier,
                            PACEInfo.toParameterSpec(paceInfo.parameterId),
                            null
                        )
                        paceSucceeded = true
                    }
                }
            } catch (e: Exception) {
                Log.w(MainActivity::class.java.simpleName, e)
            }*/
            service.sendSelectApplet(paceSucceeded)
            if (!paceSucceeded) {
                try {
                    service.getInputStream(PassportService.EF_COM).read()
                } catch (e: Exception) {
                    service.doBAC(bacKey)
                }
            }
            val dg1In = service.getInputStream(PassportService.EF_DG1)
            resultModel.dg1File = DG1File(dg1In)

            val dg2In = service.getInputStream(PassportService.EF_DG2)
            resultModel.dg2File = DG2File(dg2In)

            val dg11In = service.getInputStream(PassportService.EF_DG11)
            resultModel.dg11File = DG11File(dg11In)

            val dg12In = service.getInputStream(PassportService.EF_DG12)
            resultModel.dg12File = DG12File(dg12In)

            val sodIn = service.getInputStream(PassportService.EF_SOD)
            resultModel.sodFile = SODFile(sodIn)
            println("Pass DG! ${resultModel.dg1File}")

            //for image new
            if (resultModel.dg12File != null) {
                try {
                    val imageOfFront = resultModel.dg12File!!.imageOfFront
                    val bitmapImageOfFront =
                        BitmapFactory.decodeByteArray(imageOfFront, 0, imageOfFront.size)
                    resultModel.imageOfFront = bitmapImageOfFront
                } catch (e: Exception) {
                    Log.e(TAG, "Additional document image front: $e")
                }
            }

            // We perform Chip Authentication using Data Group 14
//            doChipAuth(service)

            // Then Passive Authentication using SODFile
//            doPassiveAuth()


            val allFaceImageInfos: MutableList<FaceImageInfo> = ArrayList()
            val faceInfos = resultModel.dg2File!!.faceInfos
            for (faceInfo in faceInfos) {
                allFaceImageInfos.addAll(faceInfo.faceImageInfos)
            }
            if (!allFaceImageInfos.isEmpty()) {
                val faceImageInfo = allFaceImageInfos.iterator().next()
                val imageLength = faceImageInfo.imageLength
                val dataInputStream = DataInputStream(faceImageInfo.imageInputStream)
                val buffer = ByteArray(imageLength)
                dataInputStream.readFully(buffer, 0, imageLength)
                val inputStream: InputStream = ByteArrayInputStream(buffer, 0, imageLength)

                resultModel.bitmap = ImageUtil.decodeImage(
                    this, faceImageInfo.mimeType, inputStream,buffer
                )
                resultModel.imageBase64 = Base64.encodeToString(buffer, Base64.DEFAULT)
            }


        } catch (e: Exception) {
            println("Exeception:$e")
        }

    }

    private fun doChipAuth(service: PassportService) {
        try {
            val dg14In = service.getInputStream(PassportService.EF_DG14)
            resultModel.dg14Encoded = IOUtils.toByteArray(dg14In)
            val dg14InByte = ByteArrayInputStream(resultModel.dg14Encoded)
            resultModel.dg14File = DG14File(dg14InByte)
            val dg14FileSecurityInfos = resultModel.dg14File!!.securityInfos
            for (securityInfo in dg14FileSecurityInfos) {
                if (securityInfo is ChipAuthenticationPublicKeyInfo) {
                    val publicKeyInfo = securityInfo
                    val keyId = publicKeyInfo.keyId
                    val publicKey = publicKeyInfo.subjectPublicKey
                    val oid = publicKeyInfo.objectIdentifier
                    service.doEACCA(
                        keyId,
                        ChipAuthenticationPublicKeyInfo.ID_CA_ECDH_AES_CBC_CMAC_256,
                        oid,
                        publicKey
                    )
                    resultModel.chipAuthSucceeded = true
                }
            }
        } catch (e: Exception) {
            Log.w(MainActivity::class.java.simpleName, e)
        }

    }

    private fun doPassiveAuth() {
        try {
            val digest = MessageDigest.getInstance(
                resultModel.sodFile!!.digestAlgorithm
            )
            val dataHashes = resultModel.sodFile!!.dataGroupHashes
            var dg14Hash: ByteArray? = ByteArray(0)
            if (resultModel.chipAuthSucceeded) {
                dg14Hash = digest.digest(resultModel.dg14Encoded)
            }
            val dg1Hash = digest.digest(resultModel.dg1File!!.encoded)
            val dg2Hash = digest.digest(resultModel.dg2File!!.encoded)
            if (Arrays.equals(dg1Hash, dataHashes[1]) && Arrays.equals(
                    dg2Hash,
                    dataHashes[2]
                ) && (!resultModel.chipAuthSucceeded || Arrays.equals(dg14Hash, dataHashes[14]))
            ) {
                // We retrieve the CSCA from the german master list
                val asn1InputStream: ASN1InputStream =
                    ASN1InputStream(ContextThemeWrapper().assets.open("masterList"))

                var p: ASN1Primitive?
                val keystore = KeyStore.getInstance(KeyStore.getDefaultType())
                keystore.load(null, null)
                val cf = CertificateFactory.getInstance("X.509")
                while (asn1InputStream.readObject().also { p = it } != null) {
                    val asn1 = ASN1Sequence.getInstance(p)
                    require(!(asn1 == null || asn1.size() == 0)) { "null or empty sequence passed." }
                    require(asn1.size() == 2) { "Incorrect sequence size: " + asn1.size() }
                    val certSet = ASN1Set.getInstance(asn1.getObjectAt(1))
                    for (i in 0 until certSet.size()) {
                        val certificate = Certificate.getInstance(certSet.getObjectAt(i))
                        val pemCertificate = certificate.encoded
                        val javaCertificate =
                            cf.generateCertificate(ByteArrayInputStream(pemCertificate))
                        keystore.setCertificateEntry(i.toString(), javaCertificate)
                    }
                }
                val docSigningCertificates = resultModel.sodFile!!.docSigningCertificates
                for (docSigningCertificate in docSigningCertificates) {
                    docSigningCertificate.checkValidity()
                }

                // We check if the certificate is signed by a trusted CSCA
                // TODO: verify if certificate is revoked
                val cp = cf.generateCertPath(docSigningCertificates)
                val pkixParameters = PKIXParameters(keystore)
                pkixParameters.isRevocationEnabled = false
                val cpv = CertPathValidator.getInstance(CertPathValidator.getDefaultType())
                cpv.validate(cp, pkixParameters)
                var sodDigestEncryptionAlgorithm =
                    resultModel.sodFile!!.docSigningCertificate.sigAlgName
                var isSSA = false
                if (sodDigestEncryptionAlgorithm == "SSAwithRSA/PSS") {
                    sodDigestEncryptionAlgorithm = "SHA256withRSA/PSS"
                    isSSA = true
                }
                val sign = Signature.getInstance(sodDigestEncryptionAlgorithm)
                if (isSSA) {
                    sign.setParameter(
                        PSSParameterSpec(
                            "SHA-256",
                            "MGF1",
                            MGF1ParameterSpec.SHA256,
                            32,
                            1
                        )
                    )
                }
                sign.initVerify(resultModel.sodFile!!.docSigningCertificate)
                sign.update(resultModel.sodFile!!.eContent)
                resultModel.passiveAuthSuccess = sign.verify(resultModel.sodFile!!.encryptedDigest)
            }
        } catch (e: Exception) {
            Log.w(MainActivity::class.java.simpleName, e)
        }

    }
}