package com.aegismed.app.domain

import com.aegismed.app.data.db.InventoryEntity
import com.aegismed.app.data.db.MedicationEntity

object InventoryManager {

    data class StockStatus(
        val inventory: InventoryEntity,
        val medication: MedicationEntity,
        val dosesRemaining: Int,
        val isLow: Boolean,
        val daysLeftEstimate: Double?
    )

    fun dosesRemaining(inv: InventoryEntity): Int =
        if (inv.unitsPerDose <= 0.0) 0 else (inv.unitsOnHand / inv.unitsPerDose).toInt()

    fun statusFor(inv: InventoryEntity, med: MedicationEntity, dailyDosesPerDay: Double?): StockStatus {
        val remaining = dosesRemaining(inv)
        val daysLeft = if (dailyDosesPerDay != null && dailyDosesPerDay > 0)
            inv.unitsOnHand / (inv.unitsPerDose * dailyDosesPerDay) else null
        return StockStatus(
            inventory = inv,
            medication = med,
            dosesRemaining = remaining,
            isLow = inv.unitsOnHand <= inv.refillThreshold,
            daysLeftEstimate = daysLeft
        )
    }
}
