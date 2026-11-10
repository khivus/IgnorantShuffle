package dev.ignorantshuffle.companion

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class StatisticsRange(val label: String, val days: Long?) {
    TODAY("Today",1), WEEK("7 days",7), MONTH("30 days",30), ALL("All time",null);
    fun since(today: LocalDate = LocalDate.now()): String = days?.let { today.minusDays(it-1).toString() } ?: ""
}

@Composable
internal fun StatisticsPage(m: AimpController, export: (String) -> Unit) {
    val repository=m.statistics
    var range by rememberSaveable { mutableStateOf(StatisticsRange.WEEK) }
    var view by rememberSaveable { mutableStateOf("Overview") }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf(false) }
    LaunchedEffect(range) {
        while(true) { repository.refresh(range.since()); delay(5000) }
    }
    val s=repository.snapshot
    val artists=remember(s) { s.tracks.filter { it.artist.isNotBlank() }.groupBy { it.artist }.map { (name,tracks) ->
        TrackStatistics(name,name,"",tracks.sumOf { it.ms },tracks.sumOf { it.plays },tracks.sumOf { it.exits })
    }.sortedWith(compareByDescending<TrackStatistics> { it.plays }.thenByDescending { it.ms }) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Text("On-device listening",style=MaterialTheme.typography.titleLarge)
            Text("Only observed AIMP playback. Statistics stay in private storage, excluded from backup. Exports contain all retained data and are saved only where you choose.",style=MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(repository.collecting,{repository.pause(!it)},enabled=repository.ready && repository.error==null)
                    Text(if(repository.collecting) "Collecting" else "Paused",Modifier.padding(start=8.dp))
                }
                Box {
                    TextButton(onClick={tools=true}) { Text("Data") }
                    DropdownMenu(expanded=tools,onDismissRequest={tools=false}) {
                        DropdownMenuItem(text={Text("Export all CSV")},onClick={tools=false;export("csv")},enabled=repository.ready)
                        DropdownMenuItem(text={Text("Export all JSON")},onClick={tools=false;export("json")},enabled=repository.ready)
                        DropdownMenuItem(text={Text("Clear statistics")},onClick={tools=false;confirmClear=true},enabled=repository.ready)
                    }
                }
            }
            repository.exportStatus?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                StatisticsRange.entries.forEach { option -> StateChip(range==option,{range=option},label={Text(option.label)}) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                listOf("Overview","Rankings","History","Track totals","Shuffle").forEach { name -> StateChip(view==name,{view=name},label={Text(name)}) }
            }
        }
        repository.error?.let { message -> item { Text("Statistics unavailable: $message",color=MaterialTheme.colorScheme.error) } }
        if(!repository.ready) item { Text("Loading statistics…") }
        if(view=="Overview") {
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Metric("Listening",listeningTime(s.listeningMs),Modifier.weight(1f))
                Metric("Qualified plays",s.plays.toString(),Modifier.weight(1f))
            } }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Metric("Unique tracks",s.tracks.count { it.ms>0 }.toString(),Modifier.weight(1f))
                Metric("Unique artists",artists.count { it.ms>0 }.toString(),Modifier.weight(1f))
            } }
            item {
                val heard=s.tracks.filter { it.ms>0 }.map { it.key }.toSet()
                val catalog=m.tracks.map { it.key }.toSet()
                Metric("Library coverage",if(catalog.isEmpty()) "Connect AIMP to compare its library" else
                    "${heard.intersect(catalog).size} / ${catalog.size} exposed tracks (${heard.intersect(catalog).size*100/catalog.size}%)")
            }
            item {
                Text("Daily listening",style=MaterialTheme.typography.titleMedium)
                val today=LocalDate.now()
                val count=range.days?: minOf(30L, if(s.days.isEmpty()) 1L else java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(s.days.keys.first()),today)+1).coerceAtLeast(1)
                val dates=(0 until count.toInt()).map { today.minusDays(count-1-it).toString() }
                val values=dates.map { s.days[it]?:0L }
                val color=MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxWidth().height(130.dp).semantics { contentDescription=dates.zip(values).joinToString { "${it.first}: ${listeningTime(it.second)}" } }) {
                    val max=(values.maxOrNull()?:0L).coerceAtLeast(1)
                    val width=size.width/values.size
                    values.forEachIndexed { i,v ->
                        val h=size.height*v.toFloat()/max
                        drawRect(color,Offset(i*width+2,size.height-h),Size((width-4).coerceAtLeast(1f),h))
                    }
                }
                Text("${dates.first()} → ${dates.last()} · ${listeningTime(values.maxOrNull()?:0)} max/day",style=MaterialTheme.typography.labelSmall)
                if(range==StatisticsRange.ALL) Text("Chart shows the latest 30 days; totals include all time.",style=MaterialTheme.typography.bodySmall)
                Text("Early exits: ${s.exits} (left before qualifying). A play qualifies after 30 seconds or half the duration, whichever is shorter.",style=MaterialTheme.typography.bodySmall)
            }
            if(s.tracks.isNotEmpty()) item { Text("Top track: ${s.tracks.first().title}\nTop artist: ${artists.firstOrNull()?.title ?: "Unknown"}") }
        }
        if(view=="Rankings") {
            item { Text("Top tracks · qualified plays, then listening time",style=MaterialTheme.typography.titleMedium) }
            items(s.tracks.take(100),key={"rank:${it.key}"}) { track -> StatisticsTrackRow(track) }
            item { Text("Top artists",style=MaterialTheme.typography.titleMedium) }
            items(artists.take(100),key={"artist:${it.key}"}) { artist -> StatisticsTrackRow(artist) }
            item { Text("Rankings show up to 100 entries. All per-track totals are searchable in Track totals.",style=MaterialTheme.typography.bodySmall) }
        }
        if(view=="Track totals") {
            item { OutlinedTextField(query,{query=it},label={Text("Search title or artist")},modifier=Modifier.fillMaxWidth(),singleLine=true) }
            items(s.tracks.filter { it.title.contains(query,true)||it.artist.contains(query,true) },key={it.key}) { StatisticsTrackRow(it) }
        }
        if(view=="History") {
            item { Text("Recent history · latest 200 observed sessions in this range",style=MaterialTheme.typography.bodySmall) }
            items(s.history,key={it.id}) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                    Text(entry.title,style=MaterialTheme.typography.titleMedium); Text(entry.artist)
                    Text("${Instant.ofEpochMilli(entry.started).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d HH:mm"))} · ${listeningTime(entry.listeningMs)} · ${if(entry.qualified) "Qualified" else if(entry.earlyExit) "Early exit" else "Observed"}",style=MaterialTheme.typography.bodySmall)
                } }
            }
        }
        if(view=="Shuffle") {
            item { Metric("Rerolls",(s.shuffle["reroll"]?:0).toString()) }
            item { Metric("Cooldown fallbacks",(s.shuffle["cooldown_fallback"]?:0).toString()) }
            item { Metric("Finite passes started / completed","${s.shuffle["pass_started"]?:0} / ${s.shuffle["pass_completed"]?:0}") }
            item { Metric("Current finite pass",if(m.settings.finite) "${m.passCount} identities heard · ${m.preview.remaining} eligible remaining (live)" else "Off") }
            item { Text("Fallbacks count confirmed selections, not previews. Current progress is live; historical counters follow the date filter.",style=MaterialTheme.typography.bodySmall) }
        }
        if(s.tracks.isEmpty()) item { Text("No observed listening in this range. Start AIMP playback; previews and paused time do not count.") }
    }
    if(confirmClear) AlertDialog(onDismissRequest={confirmClear=false},title={Text("Clear all statistics?")},
        text={Text("Permanently delete listening history, totals and shuffle counters on this device. Saved weights and diagnostic logs are kept. Listening-balance adjustments start fresh.")},
        confirmButton={TextButton(onClick={repository.clear();confirmClear=false}) {Text("Clear statistics")}},
        dismissButton={TextButton(onClick={confirmClear=false}) {Text("Cancel")}})
}

@Composable
private fun Metric(label: String,value: String,modifier: Modifier=Modifier) {
    Card(modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(label,style=MaterialTheme.typography.labelLarge); Text(value,style=MaterialTheme.typography.titleLarge) } }
}
@Composable
private fun StatisticsTrackRow(track: TrackStatistics) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text(track.title,style=MaterialTheme.typography.titleMedium)
        if(track.artist.isNotBlank()) Text(track.artist)
        Text("${track.plays} plays · ${listeningTime(track.ms)} · ${track.exits} early exits",style=MaterialTheme.typography.bodySmall)
    } }
}
internal fun listeningTime(ms: Long): String = "${ms/3600000}h ${(ms/60000)%60}m ${(ms/1000)%60}s"
