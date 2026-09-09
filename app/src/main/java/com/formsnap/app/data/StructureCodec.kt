package com.formsnap.app.data

import com.formsnap.app.processing.*
import com.formsnap.app.domain.model.*
import org.json.JSONArray
import org.json.JSONObject

/** Versioned payload for one bounded page graph, its original OCR evidence and derived logical cells. */
internal object StructureCodec {
    fun encode(p: StructureProposal): String = JSONObject().apply {
        put("version",1); put("source",p.sourceId); put("errors",p.assignmentErrors)
        put("grid",JSONObject().apply {
            val g=p.grid
            put("xs",JSONArray(g.xs)); put("ys",JSONArray(g.ys)); put("width",g.width); put("height",g.height)
            put("start",g.headerStart); put("end",g.headerEnd); put("outcome",g.outcome.name); put("reasons",JSONArray(g.reasons))
            put("merged",JSONArray(g.merged.map(::shape)))
            g.quad?.let { q -> put("quad",JSONArray(listOf(q.topLeft,q.topRight,q.bottomRight,q.bottomLeft).map { JSONArray(listOf(it.x,it.y)) })) }
        })
        put("text",JSONArray(p.text.map { JSONObject().put("text",it.text).put("region",region(it.region)).put("confidence",if(it.confidence.isFinite())it.confidence.toDouble() else 0.0) }))
        put("paths",JSONArray(p.headerPaths.map(::JSONArray)))
        put("cells",JSONArray(p.logicalCells.map { JSONObject().put("shape",shape(it.shape)).put("raw",it.candidate.rawValue ?: JSONObject.NULL)
            .put("region",region(it.candidate.region)).put("reliability",it.candidate.reliability.name) }))
    }.toString()
    fun decode(payload: String): StructureProposal {
        val p=JSONObject(payload); require(p.getInt("version")==1)
        val g=p.getJSONObject("grid")
        val q=g.optJSONArray("quad")?.let { a -> (0..3).map { a.getJSONArray(it).let { xy -> TablePoint(xy.getDouble(0).toFloat(),xy.getDouble(1).toFloat()) } } }
        val grid=TableGrid(g.getJSONArray("xs").ints(),g.getJSONArray("ys").ints(),g.getInt("width"),g.getInt("height"),
            g.getJSONArray("merged").objects(::readShape),g.getInt("start"),g.getInt("end"),q?.let { TableQuad(it[0],it[1],it[2],it[3]) },
            StructureOutcome.valueOf(g.getString("outcome")),g.getJSONArray("reasons").strings())
        val words=p.getJSONArray("text").objects { TextEvidence(it.getString("text"),readRegion(it.getJSONArray("region")),it.getDouble("confidence").toFloat()) }
        val cells=p.getJSONArray("cells").objects { LogicalCell(readShape(it.getJSONObject("shape")),CandidateCell(
            if(it.isNull("raw"))null else it.getString("raw"),readRegion(it.getJSONArray("region")),RecognitionReliability.valueOf(it.getString("reliability")))) }
        val paths=p.getJSONArray("paths").let { a -> (0 until a.length()).map { a.getJSONArray(it).strings() } }
        return StructureProposal(p.getString("source"),grid,words,cells,paths,p.getInt("errors"))
    }
    private fun shape(c: GridCell)=JSONObject().put("row",c.row).put("column",c.column).put("rows",c.rowSpan).put("columns",c.colSpan)
    private fun readShape(o: JSONObject)=GridCell(o.getInt("row"),o.getInt("column"),o.getInt("rows"),o.getInt("columns"))
    private fun region(r: SourceRegion)=JSONArray(listOf(r.left,r.top,r.right,r.bottom))
    private fun readRegion(a: JSONArray)=SourceRegion(a.getDouble(0).toFloat(),a.getDouble(1).toFloat(),a.getDouble(2).toFloat(),a.getDouble(3).toFloat())
    private fun JSONArray.ints()=(0 until length()).map(::getInt)
    private fun JSONArray.strings()=(0 until length()).map(::getString)
    private fun <T> JSONArray.objects(read: (JSONObject)->T)=(0 until length()).map { read(getJSONObject(it)) }
}
