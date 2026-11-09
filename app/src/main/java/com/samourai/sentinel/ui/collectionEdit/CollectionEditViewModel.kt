package com.samourai.sentinel.ui.collectionEdit

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.samourai.sentinel.data.PubKeyCollection
import com.samourai.sentinel.data.PubKeyModel
import com.samourai.sentinel.data.repository.CollectionRepository
import com.samourai.sentinel.data.repository.TransactionsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent
import org.koin.java.KoinJavaComponent.inject

class CollectionEditViewModel : ViewModel() {
    private val repository: CollectionRepository by inject(CollectionRepository::class.java)
    private val transactionsRepository: TransactionsRepository by inject(TransactionsRepository::class.java)
    val message: MutableLiveData<String> = MutableLiveData("")
    private val pubKeyCollectionLiveData: MutableLiveData<PubKeyCollection> = MutableLiveData()
    private val pubKeys: MutableLiveData<ArrayList<PubKeyModel>> = MutableLiveData()

    /**
     * Set synchronously on the first admission, before the handoff token
     * is taken. An in-process recreation keeps this ViewModel, so the
     * activity must not take the token or import again. A process death
     * constructs a new ViewModel and admits from scratch.
     */
    private var editorAdmitted = false

    /**
     * The name field, captured when the activity is saved. It is not the
     * repository label: Save is what publishes that. Null until the first
     * save-instance, so a fresh screen still shows the stored label.
     */
    private var nameDraft: String? = null

    /** Handoff imports requested by this editor. Recreation must not add more. */
    internal var importsRequested: Int = 0
        private set

    private fun addNew(pubKeyCollection: PubKeyCollection) {
        repository.addNew(pubKeyCollection)
    }


    /**
     * True only after an add or an update. A collection or key list that
     * has not been delivered yet returns false and writes nothing, so the
     * activity can stay open and try again.
     */
    fun save(): Boolean = persist(label = null)

    /**
     * Same as [save], and publishes [label] onto the collection only once
     * the write is actually going to happen.
     */
    fun save(label: String): Boolean = persist(label)

    private fun persist(label: String?): Boolean {
        val collection = pubKeyCollectionLiveData.value ?: return false
        val keys = pubKeys.value ?: return false
        if (label != null) {
            collection.collectionLabel = label
        }
        if (collection.id == "") {
            // #93: publication point - publishes the fully-formed
            // scratch list (birth-phase object; nothing can
            // observe it between here and addNew).
            collection.pubs = ArrayList(keys)
            addNew(collection)
            message.postValue("Saved...")
        } else {
            // #93: publication point - the swap publishes the
            // fully-formed scratch list; update()'s monitor owns
            // structural safety from here (deep copy under the
            // outer lock).
            collection.pubs = ArrayList(keys)
            repository.update(collection)
            // Save used to post this same instance back through
            // getCollection(). The screen's observer binds the name
            // field and rebuilds the key list on every emission, so
            // that post was an intermediate reset in the middle of
            // leaving the screen (another setText, another watcher,
            // another recycler setup). Persistence is the update()
            // above; the field draft is committed by the activity
            // before this runs.
            message.postValue("Collection Updated")
        }
        return true
    }

    internal fun isEditorAdmitted(): Boolean = editorAdmitted

    internal fun admitEditor() {
        editorAdmitted = true
    }

    internal fun rememberNameDraft(text: String) {
        nameDraft = text
    }

    internal fun nameDraft(): String? = nameDraft

    internal fun recordImportRequest() {
        importsRequested += 1
    }


    fun setCollection(pubKeyCollection: PubKeyCollection) {
        pubKeyCollectionLiveData.postValue(pubKeyCollection)
    }

    fun setPubKeys(items: ArrayList<PubKeyModel>) {
        // #93: the VM owns a scratch copy from handoff on - callers may
        // pass the findById-LIVE pubs list (CollectionEditActivity's
        // checkIntent paths do), and every edit below would otherwise
        // race a concurrent snapshot deep-copy. Scratch keeps the live
        // list untouched until save() publishes the fully-formed state
        // by reference swap.
        pubKeys.postValue(ArrayList(items))
    }

    fun getCollection(): LiveData<PubKeyCollection> {
        return pubKeyCollectionLiveData;
    }

    fun getPubKeys(): LiveData<ArrayList<PubKeyModel>> {
        return pubKeys
    }

    fun removePubKey(it: Int) {
        val keys = pubKeys.value
        keys?.removeAt(it)
        pubKeys.postValue(keys)
        this.save()
    }

    fun updateKey(index: Int, model: PubKeyModel) {
        val keys = pubKeys.value
        if (keys != null) {
            keys[index] = model
            this.pubKeys.postValue(keys)
        }
        this.save()
    }

    suspend fun removeCollection(id: String) {
        val collection = repository.findById(id)
        collection?.pubs?.forEach {
            transactionsRepository.removeTxsRelatedToPubKey(it, collection.id)
        }
        repository.remove(id)
    }


}