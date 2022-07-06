package com.application.passportreaderkotlin.model

import android.graphics.Bitmap
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.*


class ResultModel {
    var dg1File: DG1File? = null
    var dg2File: DG2File? = null
    var dg11File: DG11File? = null
    var dg12File: DG12File? = null
    var dg14File: DG14File? = null
    var sodFile: SODFile? = null
    var imageBase64: String? = null
    var bitmap: Bitmap? = null
    var chipAuthSucceeded = false
    var passiveAuthSuccess = false
    var dg14Encoded = ByteArray(0)

    var imageOfFront: Bitmap? = null

    constructor()

    constructor(
        dg1File: DG1File?,
        dg2File: DG2File?,
        dg11File: DG11File?,
        dg12File: DG12File?,
        dg14File: DG14File?,
        sodFile: SODFile?,
        imageBase64: String?,
        bitmap: Bitmap?,
        chipAuthSucceeded: Boolean,
        passiveAuthSuccess: Boolean,
        dg14Encoded: ByteArray,
        imageOfFront: Bitmap
    ) {
        this.dg1File = dg1File
        this.dg2File = dg2File
        this.dg11File = dg11File
        this.dg12File = dg12File
        this.dg14File = dg14File
        this.sodFile = sodFile
        this.imageBase64 = imageBase64
        this.bitmap = bitmap
        this.chipAuthSucceeded = chipAuthSucceeded
        this.passiveAuthSuccess = passiveAuthSuccess
        this.dg14Encoded = dg14Encoded
        this.imageOfFront = imageOfFront
    }


}