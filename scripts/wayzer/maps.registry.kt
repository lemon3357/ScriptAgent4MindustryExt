package wayzer

import cf.wayzer.scriptAgent.Event
import cf.wayzer.scriptAgent.define.ISubScript
import cf.wayzer.scriptAgent.emit
import coreLibrary.lib.PlaceHoldString
import coreMindustry.lib.ContentHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mindustry.game.Gamemode
import mindustry.io.SaveIO
import mindustry.maps.Map

data class MapInfo(val id: Int, val map: Map, val mode: Gamemode) {
    override fun equals(other: Any?): Boolean {
        if (other !is MapInfo) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id
    }
}

abstract class MapProvider {
    /**should support "all"*/
    open val supportFilter: Set<String> = baseFilter

    /**@param filter all is lowerCase */
    abstract fun getMaps(filter: String = "all"): Collection<MapInfo>

    /**@param id may not exist in getMaps*/
    open suspend fun findById(id: Int, reply: ((PlaceHoldString) -> Unit)? = null): MapInfo? =
        getMaps().find { it.id == id }

    companion object {
        val baseFilter = setOf("all", "display", "pvp", "attack", "survive")
        fun List<MapInfo>.filterWhen(b: Boolean, body: (MapInfo) -> Boolean): List<MapInfo> {
            return if (b) filter(body) else this
        }

        fun List<MapInfo>.filterByMode(filter: String) = this
            .filterWhen(filter == "survive") { it.mode == Gamemode.survival }
            .filterWhen(filter == "attack") { it.mode == Gamemode.attack }
            .filterWhen(filter == "pvp") { it.mode == Gamemode.pvp }
    }
}

class GetNextMapEvent(val previous: MapInfo?, var mapInfo: MapInfo) : Event, Event.Cancellable {
    override var cancelled: Boolean = false
    override val handler: Event.Handler get() = Companion

    companion object : Event.Handler()
}

object MapRegistry : MapProvider() {
    private val providers = mutableSetOf<MapProvider>()
    fun register(script: ISubScript, provider: MapProvider) {
        script.onDisable {
            providers.remove(provider)
        }
        providers.add(provider)
    }

    override val supportFilter: Set<String> get() = providers.flatMapTo(mutableSetOf()) { it.supportFilter }
    override fun getMaps(filter: String) = providers.flatMapTo(mutableSetOf()) { it.getMaps(filter) }

    /**Dispatch should be Dispatchers.game*/
    override suspend fun findById(id: Int, reply: ((PlaceHoldString) -> Unit)?): MapInfo? {
        return providers.asFlow().map { it.findById(id, reply) }.filterNotNull().firstOrNull()
    }

    /**
     * @return >=1 if Found, 0 if not found
     */
    fun getId(map: Map) = map.tags.getInt("id", 0)
    fun findByMap(map: Map) = runBlocking { findById(getId(map))?.takeIf { map.name() == it.map.name() } }

    fun nextMapInfo(previous: MapInfo? = null, mode: Gamemode = Gamemode.survival, filter: String = "all"): MapInfo {
        val maps = getMaps(filter)
        val next = maps.filter { it.mode == mode && it != previous }.randomOrNull()
            ?: maps.random()
        if (!SaveIO.isSaveValid(next.map.file)) {
            ContentHelper.logToConsole("[yellow]invalid map ${next.map.file.nameWithoutExtension()}, auto change")
            return nextMapInfo(previous, mode)
        }
        return GetNextMapEvent(previous, next).emit().mapInfo
    }
}