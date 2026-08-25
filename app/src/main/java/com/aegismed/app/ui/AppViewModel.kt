package com.aegismed.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aegismed.app.data.db.AegisDatabase
import com.aegismed.app.data.db.AnchorEventEntity
import com.aegismed.app.data.db.CareContactEntity
import com.aegismed.app.data.db.InventoryEntity
import com.aegismed.app.data.db.MedicationEntity
import com.aegismed.app.data.db.ScheduleRuleEntity
import com.aegismed.app.data.repo.MedRepository
import com.aegismed.app.domain.AnchorKind
import com.aegismed.app.domain.InventoryManager
import com.aegismed.app.domain.InteractionEngine
import com.aegismed.app.domain.InteractionResult
import com.aegismed.app.domain.ScheduleEngine
import com.aegismed.app.util.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext
    private val db = AegisDatabase.get(context)

    data class NfcRequest(val medId: Long, val bindIfUnknown: Boolean)

    private val _now = MutableStateFlow(System.currentTimeMillis())
    private val _refresh = MutableStateFlow(0L)
    private val _nfcRequest = MutableStateFlow<NfcRequest?>(null)
    val nfcRequest: StateFlow<NfcRequest?> = _nfcRequest.asStateFlow()

    private val _nfcVerifiedMed = MutableStateFlow<Long?>(null)
    val nfcVerifiedMed: StateFlow<Long?> = _nfcVerifiedMed.asStateFlow()

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast

    private val interactionEngine by lazy { InteractionEngine.get(context) }

    val meds: StateFlow<List<MedicationEntity>> = db.medicationDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val contacts: StateFlow<List<CareContactEntity>> = db.careContactDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fontScale: StateFlow<Float> = Settings.fontScaleFlow(context)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.15f)

    val nightRoutine: StateFlow<Boolean> = Settings.nightRoutineFlow(context)
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val onlineLookups: StateFlow<Boolean> = Settings.onlineLookupsFlow(context)
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        viewModelScope.launch {
            while (true) {
                delay(30_000)
                _now.value = System.currentTimeMillis()
            }
        }
    }

    data class DashboardState(
        val loading: Boolean = true,
        val actionNeeded: List<ScheduleEngine.DoseSlot> = emptyList(),
        val upcoming: List<ScheduleEngine.DoseSlot> = emptyList(),
        val completedToday: Int = 0,
        val totalToday: Int = 0,
        val adherencePct30d: Int = 100,
        val stocks: List<InventoryManager.StockStatus> = emptyList(),
        val interactions: List<InteractionResult> = emptyList(),
        val anchorsMarkedToday: Set<AnchorKind> = emptySet()
    )

    val dashboard: StateFlow<DashboardState> =
        combine(
            _now, _refresh, nightRoutine, meds, db.inventoryDao().observeAll()
        ) { now, _, night, medList, invs ->
            Triple(now to night, medList, invs)
        }.let { baseFlow ->
            combine(baseFlow, db.remoteInteractionDao().observeAll()) { base, remoteRows ->
                buildDashboard(base.first.first, base.first.second, base.second, base.third, remoteRows)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())

    private suspend fun buildDashboard(
        now: Long,
        night: Boolean,
        medList: List<MedicationEntity>,
        invs: List<InventoryEntity>,
        remoteInteractions: List<com.aegismed.app.data.db.RemoteInteractionEntity>
    ): DashboardState {
        if (medList.isEmpty()) return DashboardState(loading = false)

        val slots = runCatching { MedRepository.buildPlan(context, now) }.getOrDefault(emptyList())

        val zone = ZoneId.systemDefault()
        val todayStart = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()

        val todaySlots = slots.filter { it.scheduledFor >= todayStart }
        val actionNeeded = slots.filter {
            it.status == com.aegismed.app.domain.DoseStatus.DUE ||
                it.status == com.aegismed.app.domain.DoseStatus.LATE ||
                it.status == com.aegismed.app.domain.DoseStatus.MISSED
        }
        val upcoming = slots.filter {
            it.status == com.aegismed.app.domain.DoseStatus.UPCOMING
        }

        val series = runCatching { MedRepository.adherenceStats(context, 30) }.getOrDefault(emptyMap())
        val taken = series.values.sumOf { it.first }
        val missed = series.values.sumOf { it.second }
        val adherence = if (taken + missed > 0) taken * 100 / (taken + missed) else 100

        val stocks = invs.mapNotNull { inv ->
            val med = medList.firstOrNull { it.id == inv.medicationId } ?: return@mapNotNull null
            InventoryManager.statusFor(inv, med, null)
        }.sortedBy { it.dosesRemaining }

        val interactions = runCatching {
            interactionEngine.evaluateWithRemote(medList, remoteInteractions)
        }.getOrDefault(emptyList())

        val markedToday = mutableSetOf<AnchorKind>()
        val todayEpochDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        for (kind in AnchorKind.entries) {
            if (db.anchorEventDao().find(kind.ordinal, todayEpochDay) != null) {
                markedToday += kind
            }
        }

        return DashboardState(
            loading = false,
            actionNeeded = actionNeeded,
            upcoming = upcoming,
            completedToday = todaySlots.count { it.status == com.aegismed.app.domain.DoseStatus.TAKEN },
            totalToday = todaySlots.size,
            adherencePct30d = adherence,
            stocks = stocks,
            interactions = interactions,
            anchorsMarkedToday = markedToday
        )
    }

    fun refresh() {
        _now.value = System.currentTimeMillis()
        _refresh.value = System.currentTimeMillis()
    }

    fun logDose(slot: ScheduleEngine.DoseSlot, taken: Boolean) {
        viewModelScope.launch {
            val result = MedRepository.logDose(context, slot, taken)
            if (result.isSuccess) {
                _toast.emit(if (taken) "Dose logged — ${slot.medicationName}" else "Dose skipped")
                refresh()
            } else {
                _toast.emit("Could not save dose")
            }
        }
    }

    fun markAnchor(kind: AnchorKind) {
        viewModelScope.launch {
            MedRepository.markAnchor(context, kind)
            _toast.emit("${kind.label} recorded")
            refresh()
        }
    }

    fun toggleNightRoutine() {
        viewModelScope.launch {
            Settings.setNightRoutine(context, !nightRoutine.value)
            refresh()
        }
    }

    fun setFontScale(v: Float) {
        viewModelScope.launch { Settings.setFontScale(context, v) }
    }

    fun saveMedication(med: MedicationEntity, rule: ScheduleRuleEntity, initialUnits: Double?, unitsPerDose: Double, threshold: Double) {
        viewModelScope.launch {
            try {
                MedRepository.saveMedication(context, med, rule, initialUnits, unitsPerDose, threshold)
                _toast.emit("Saved ${med.name}")
                refresh()
            } catch (e: Exception) {
                _toast.emit("Save failed")
            }
        }
    }

    fun setMedActive(medId: Long, active: Boolean) {
        viewModelScope.launch {
            MedRepository.setMedActive(context, medId, active)
            refresh()
        }
    }

    fun refill(medId: Long, unitsAdded: Double) {
        viewModelScope.launch {
            MedRepository.refill(context, medId, unitsAdded)
            _toast.emit("Refill recorded")
            refresh()
        }
    }

    suspend fun refillSuspend(medId: Long, unitsAdded: Double) {
        MedRepository.refill(context, medId, unitsAdded)
        refresh()
    }

    suspend fun searchDrugs(query: String): List<com.aegismed.app.data.db.DrugCacheEntity> =
        runCatching { com.aegismed.app.data.repo.ClinicalLookupRepository.autofillSearch(context, query) }
            .getOrDefault(emptyList())

    fun setOnlineLookups(enabled: Boolean) {
        viewModelScope.launch {
            Settings.setOnlineLookups(context, enabled)
        }
    }

    fun refreshRemoteInteractions(onDone: (Result<Int>) -> Unit) {
        viewModelScope.launch {
            val result = runCatching {
                com.aegismed.app.data.repo.ClinicalLookupRepository.refreshInteractions(context)
            }
            if (result.isSuccess) {
                _toast.emit("Synced ${result.getOrNull()} NIH interaction findings")
                refresh()
            } else {
                _toast.emit("RxNorm sync failed: ${result.exceptionOrNull()?.message ?: "offline"}")
            }
            onDone(result)
        }
    }

    suspend fun enrichLabel(medId: Long): com.aegismed.app.data.repo.ClinicalLookupRepository.Enrichment? {
        val med = db.medicationDao().byId(medId) ?: return null
        return runCatching {
            com.aegismed.app.data.repo.ClinicalLookupRepository.enrichLabel(context, med)
        }.getOrNull()
    }

    suspend fun enrichLabelForName(
        name: String,
        knownRxcui: String?
    ): com.aegismed.app.data.repo.ClinicalLookupRepository.Enrichment? =
        runCatching {
            com.aegismed.app.data.repo.ClinicalLookupRepository.enrichLabel(
                context,
                MedicationEntity(id = 0, name = name, rxcui = knownRxcui)
            )
        }.getOrNull()

    fun saveMedicationWithRxcui(med: MedicationEntity, rule: ScheduleRuleEntity, initialUnits: Double?, unitsPerDose: Double, threshold: Double) {
        saveMedication(med, rule, initialUnits, unitsPerDose, threshold)
        if (!med.rxcui.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching {
                val saved = db.medicationDao().observeAll().first()
                    .firstOrNull { it.name.equals(med.name.trim(), ignoreCase = true) } ?: return@launch
                val rxcui = com.aegismed.app.data.repo.ClinicalLookupRepository.resolveRxcui(context, med.name)
                if (!rxcui.isNullOrBlank() && saved.rxcui != rxcui) {
                    db.medicationDao().upsert(saved.copy(rxcui = rxcui))
                    refresh()
                }
            }
        }
    }

    fun bindNfcTag(medId: Long) {
        _nfcRequest.value = NfcRequest(medId, bindIfUnknown = true)
        viewModelScope.launch { _toast.emit("Hold phone against NFC tag…") }
    }

    fun requestNfcVerification(medId: Long) {
        _nfcRequest.value = NfcRequest(medId, bindIfUnknown = false)
    }

    fun cancelNfcRequest() {
        _nfcRequest.value = null
    }

    fun clearNfcVerified() {
        _nfcVerifiedMed.value = null
    }

    suspend fun medById(id: Long): MedicationEntity? = db.medicationDao().byId(id)

    suspend fun ruleFor(medId: Long): ScheduleRuleEntity? =
        db.scheduleRuleDao().forMedication(medId).firstOrNull()

    suspend fun inventoryFor(medId: Long): InventoryEntity? = db.inventoryDao().forMedication(medId)

    fun onNfcTagDiscovered(tagHex: String) {
        val req = _nfcRequest.value ?: return
        viewModelScope.launch {
            val med = db.medicationDao().byId(req.medId) ?: return@launch
            if (req.bindIfUnknown || med.nfcTagIdHex.isNullOrBlank()) {
                db.medicationDao().upsert(med.copy(nfcTagIdHex = tagHex))
                _toast.emit("NFC tag bound to ${med.name}")
                refresh()
            } else {
                if (tagHex.equals(med.nfcTagIdHex, ignoreCase = true)) {
                    _nfcVerifiedMed.value = req.medId
                    _toast.emit("NFC verified")
                } else {
                    _toast.emit("Wrong tag for ${med.name}")
                }
            }
            _nfcRequest.value = null
        }
    }

    suspend fun verifyBarcodeForMed(medId: Long, scanned: String): Boolean {
        val med = db.medicationDao().byId(medId) ?: return false
        val bound = med.barcode
        if (bound.isNullOrBlank()) {
            db.medicationDao().upsert(med.copy(barcode = scanned.trim()))
            _toast.emit("Barcode bound to ${med.name}")
            return true
        }
        return scanned.trim().equals(bound, ignoreCase = true)
    }

    fun addContact(name: String, address: String, smsChannel: Boolean) {
        viewModelScope.launch {
            db.careContactDao().upsert(
                CareContactEntity(name = name, address = address, channelOrdinal = if (smsChannel) 0 else 1)
            )
            _toast.emit("Contact saved")
        }
    }

    fun deleteContact(id: Long) {
        viewModelScope.launch { db.careContactDao().delete(id) }
    }

    companion object {
        const val DAY_MS = 86_400_000L
    }
}
