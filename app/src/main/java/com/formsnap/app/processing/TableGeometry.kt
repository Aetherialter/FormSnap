package com.formsnap.app.processing

import kotlin.math.*

data class TablePoint(val x: Float,val y: Float)
/** Unit square to original EXIF-upright image. Only geometry is persisted, not a warped bitmap. */
data class TableQuad(val topLeft: TablePoint,val topRight: TablePoint,val bottomRight: TablePoint,val bottomLeft: TablePoint) {
    private fun coefficients(): FloatArray {
        val p=topLeft; val q=topRight; val r=bottomRight; val s=bottomLeft
        val dx1=q.x-r.x; val dx2=s.x-r.x; val dx3=p.x-q.x+r.x-s.x
        val dy1=q.y-r.y; val dy2=s.y-r.y; val dy3=p.y-q.y+r.y-s.y; val det=dx1*dy2-dx2*dy1
        require(abs(det)>1e-7)
        val g=(dx3*dy2-dx2*dy3)/det; val h=(dx1*dy3-dx3*dy1)/det
        return floatArrayOf(q.x-p.x+g*q.x,s.x-p.x+h*s.x,p.x,q.y-p.y+g*q.y,s.y-p.y+h*s.y,p.y,g,h)
    }
    fun map(u: Float,v: Float): TablePoint { val c=coefficients(); val d=c[6]*u+c[7]*v+1; return TablePoint((c[0]*u+c[1]*v+c[2])/d,(c[3]*u+c[4]*v+c[5])/d) }
    fun inverse(p: TablePoint): TablePoint {
        val c=coefficients(); val a=c[0]-p.x*c[6]; val b=c[1]-p.x*c[7]; val d=c[3]-p.y*c[6]; val e=c[4]-p.y*c[7]
        val x=p.x-c[2]; val y=p.y-c[5]; val det=a*e-b*d
        return TablePoint((x*e-b*y)/det,(a*y-x*d)/det)
    }
}

/** Bounded candidate proposal, evidence, merge and selection for table rulings. */
internal data class GeometryFrame(val grid: TableGrid,val diagnostics: StructureDiagnostics)

internal class LineGeometry(
    private val policy: StructureDetectorPolicy,
    private val checkpoint: () -> Unit = {},
) {
    var lastDiagnostics: StructureDiagnostics = StructureDiagnostics()
        private set
    private data class LineEvidence(
        val normalizedLength: Double,
        val continuity: Double,
        val localContrast: Double,
        val fragmentation: Double,
        val edgePenalty: Double,
        val score: Double,
    )

    private data class Line(
        val slope: Float,
        val intercept: Float,
        val start: Int,
        val end: Int,
        val evidence: LineEvidence,
        val mergeGroupSize: Int = 1,
        val candidateId: Int = -1,
    ) {
        fun at(v: Float)=slope*v+intercept
    }

    private data class LineResult(
        val lines: List<Line>,
        val rawCandidateCount: Int,
        val mergedCandidateCount: Int,
        val candidates: List<Line>,
    )

    fun frame(original: GrayImage): GeometryFrame? {
        val scale=max(1,ceil(max(original.width,original.height)/1000.0).toInt()); val w=original.width/scale; val h=original.height/scale
        if(w<40 || h<40)return null
        val image=GrayImage(w,h,ByteArray(w*h) { n ->
            var v=255
            for(dy in 0 until scale)for(dx in 0 until scale)v=min(v,original.value(n%w*scale+dx,n/w*scale+dy))
            v.toByte()
        })
        val horizontal=lines(image,true)
        val vertical=lines(image,false)
        val stages=mutableListOf<GeometryStageDiagnostic>()
        fun trace(stage:String,hs:List<Line>,vs:List<Line>,intersections:Int=0) {
            stages+=GeometryStageDiagnostic(stage,
                diagnostics(horizontal,hs,w,h,true).filter { it.disposition==CandidateDisposition.SELECTED },
                diagnostics(vertical,vs,h,w,false).filter { it.disposition==CandidateDisposition.SELECTED },intersections)
            lastDiagnostics=lastDiagnostics.copy(geometryStages=stages.toList(),failureStage=stage)
        }
        // Preserve proposal evidence even when no usable final grid survives selection.
        lastDiagnostics=StructureDiagnostics(
            horizontalCandidateLines=horizontal.rawCandidateCount,
            verticalCandidateLines=vertical.rawCandidateCount,
            horizontalMergedCandidateLines=horizontal.mergedCandidateCount,
            verticalMergedCandidateLines=vertical.mergedCandidateCount,
            horizontalCandidates=diagnostics(horizontal,emptyList(),w,h,true),
            verticalCandidates=diagnostics(vertical,emptyList(),h,w,false),
        )
        trace("CANDIDATE_SELECTION",horizontal.lines,vertical.lines)
        var hs=coherent(horizontal.lines,w,h);var vs=coherent(vertical.lines,h,w)
        trace("COHERENT_FAMILY",hs,vs)
        fun cross(a: Line,b: Line): TablePoint { val denominator=1-b.slope*a.slope; if(abs(denominator)<.01f)return TablePoint(Float.NaN,Float.NaN); val x=(b.slope*a.intercept+b.intercept)/denominator; return TablePoint(x,a.at(x)) }
        fun connected(a: Line,b: Line): Boolean { val p=cross(a,b); return p.x.isFinite() && p.y.isFinite() && p.x>=a.start-5 && p.x<=a.end+5 && p.y>=b.start-5 && p.y<=b.end+5 }
        fun spacingIrregular(lines: List<Line>,length: Int): Boolean {
            val positions=lines.map { it.at(length/2f) }.sorted()
            if(positions.size<5)return false
            val gaps=positions.zipWithNext().map { it.second-it.first }.filter { it>1f }
            if(gaps.size<4)return false
            val sorted=gaps.sorted(); val median=sorted[sorted.size/2]
            return median>0f && gaps.maxOrNull()!!>median*1.8f
        }
        // A sidebar can bridge otherwise separate regions with a handful of menu rules.
        // Table columns must participate in a substantial part of the recovered row family.
        // Do not apply the symmetric test to rows: merged header edges legitimately meet few columns.
        vs=vs.filter { b -> hs.count { connected(it,b) }>=max(3,ceil(hs.size*.45).toInt()) }
        repeat(2) { hs=hs.filter { a -> vs.count { connected(a,it) }>=2 }; vs=vs.filter { b -> hs.count { connected(it,b) }>=3 } }
        // Keep the coherent family when its spacing is irregular. Falling back to every raw
        // response here used to reintroduce hundreds of near-duplicate lines and could turn a
        // recoverable screen photo into SOURCE_UNUSABLE during grid quantization. The irregular
        // spacing remains explicit review evidence below.
        val irregularHorizontalSpacing=spacingIrregular(hs,w)
        val irregularVerticalSpacing=spacingIrregular(vs,h)
        trace("INTERSECTION_FILTER",hs,vs,hs.sumOf { a -> vs.count { connected(a,it) } })
        if(hs.size<3 || vs.size<2)return null
        val groups=mutableListOf<Pair<List<Line>,List<Line>>>(); val remaining=hs.toMutableSet()
        while(remaining.isNotEmpty()) {
            val a=mutableSetOf(remaining.first()); val b=mutableSetOf<Line>()
            do { val old=a.size+b.size; b+=vs.filter { v -> a.any { connected(it,v) } }; a+=hs.filter { u -> b.any { connected(u,it) } }
            } while(a.size+b.size>old)
            remaining.removeAll(a); if(a.size>=3 && b.size>=2)groups+=a.toList() to b.toList()
        }
        val best=groups.maxByOrNull { it.first.size*it.second.size } ?: return null
        hs=best.first.sortedBy { it.at(w/2f) }; vs=best.second.sortedBy { it.at(h/2f) }
        // With a strongly supported row family, isolated fragmented text bands are provisional,
        // not automatic separators. Record this exclusion so the user can restore a real gap.
        val weakRows=if(hs.count { it.evidence.continuity>.9 }>=hs.size*.8)
            hs.filter { it.evidence.continuity<.75 } else emptyList()
        hs=hs-weakRows.toSet()
        trace("GRID_FAMILY",hs,vs,hs.sumOf { a -> vs.count { connected(a,it) } })
        if(hs.size>501 || vs.size>129)return null
        val corners=listOf(cross(hs.first(),vs.first()),cross(hs.first(),vs.last()),cross(hs.last(),vs.last()),cross(hs.last(),vs.first()))
        if(corners.any { !it.x.isFinite() || !it.y.isFinite() || it.x< -w*.1 || it.y< -h*.1 || it.x>w*1.1 || it.y>h*1.1 })return null
        val points=corners.map { TablePoint((it.x*scale/original.width).coerceIn(0f,1f),(it.y*scale/original.height).coerceIn(0f,1f)) }
        val quad=TableQuad(points[0],points[1],points[2],points[3])
        lastDiagnostics=lastDiagnostics.copy(failureStage="TABLE_QUAD",proposedQuad=quad)
        val cw=(hypot(corners[1].x-corners[0].x,corners[1].y-corners[0].y)*scale).roundToInt()
        val ch=(hypot(corners[3].x-corners[0].x,corners[3].y-corners[0].y)*scale).roundToInt()
        if(cw<20 || ch<20)return null
        val mid=quad.map(.5f,.5f)
        val xs=vs.map { quad.inverse(TablePoint(it.at(mid.y*original.height/scale)*scale/original.width,mid.y)).x.times(cw).roundToInt().coerceIn(0,cw) }.distinct()
        val ys=hs.map { quad.inverse(TablePoint(mid.x,it.at(mid.x*original.width/scale)*scale/original.height)).y.times(ch).roundToInt().coerceIn(0,ch) }.distinct()
        lastDiagnostics=lastDiagnostics.copy(failureStage="GRID_RECONSTRUCTION")
        if(xs.size<2 || ys.size<3 || xs.zipWithNext().any { it.second-it.first<4 } || ys.zipWithNext().any { it.second-it.first<4 })return null
        val axis=hs.all { abs(it.slope)<.001 } && vs.all { abs(it.slope)<.001 }

        val edgeMargin=max(3f,min(w,h)*.025f)
        val leftContact=hs.any { it.start<=edgeMargin }
        val rightContact=hs.any { it.end>=w-edgeMargin }
        val topContact=vs.any { it.start<=edgeMargin }
        val bottomContact=vs.any { it.end>=h-edgeMargin }
        val middleX=vs.map { it.at(h/2f) }
        val middleY=hs.map { it.at(w/2f) }
        val leftBorderVisible=middleX.minOrNull()?.let { it<=edgeMargin }==true
        val rightBorderVisible=middleX.maxOrNull()?.let { it>=w-edgeMargin }==true
        val topBorderVisible=middleY.minOrNull()?.let { it<=edgeMargin }==true
        val bottomBorderVisible=middleY.maxOrNull()?.let { it>=h-edgeMargin }==true
        val outsideLeft=corners[0].x<0 || corners[3].x<0
        val outsideRight=corners[1].x>w || corners[2].x>w
        val outsideTop=corners[0].y<0 || corners[1].y<0
        val outsideBottom=corners[2].y>h || corners[3].y>h
        // Edge contact on an axis-aligned screenshot is common and is not clipping evidence.
        // Require a non-axis family before inferring a missing outer border.
        val frameWidthFraction=((corners.maxOf { it.x }-corners.minOf { it.x })/w).coerceIn(0f,1f)
        val frameHeightFraction=((corners.maxOf { it.y }-corners.minOf { it.y })/h).coerceIn(0f,1f)
        val contactClipEligible=frameWidthFraction>=.75f || frameHeightFraction>=.75f
        val likelyLeft=!axis && contactClipEligible && leftContact && (outsideLeft || !leftBorderVisible)
        val likelyRight=!axis && contactClipEligible && rightContact && (outsideRight || !rightBorderVisible)
        val likelyTop=!axis && contactClipEligible && topContact && (outsideTop || !topBorderVisible)
        val likelyBottom=!axis && contactClipEligible && bottomContact && (outsideBottom || !bottomBorderVisible)
        val possibleEdge=!axis && (
            (leftContact && !leftBorderVisible) ||
                (rightContact && !rightBorderVisible) ||
                (topContact && !topBorderVisible) ||
                (bottomContact && !bottomBorderVisible)
            )
        val clippingEvidence=when {
            likelyLeft && likelyRight -> ClippingEvidence.LIKELY_CLIPPED_BOTH
            likelyLeft -> ClippingEvidence.LIKELY_CLIPPED_LEFT
            likelyRight -> ClippingEvidence.LIKELY_CLIPPED_RIGHT
            possibleEdge && (!leftBorderVisible || !rightBorderVisible || !topBorderVisible || !bottomBorderVisible) -> ClippingEvidence.POSSIBLE_CLIPPING
            else -> ClippingEvidence.NO_CLIPPING_EVIDENCE
        }
        val clippingReasons=buildList {
            if(leftContact && !leftBorderVisible)add("左侧表格线接触图片边缘且外边界未见闭合。")
            if(rightContact && !rightBorderVisible)add("右侧表格线接触图片边缘且外边界未见闭合。")
            if(topContact && !topBorderVisible)add("上侧表格线接触图片边缘且外边界未见闭合。")
            if(bottomContact && !bottomBorderVisible)add("下侧表格线接触图片边缘且外边界未见闭合。")
            if(outsideLeft || outsideRight || outsideTop || outsideBottom)add("表格边界交点超出图片范围。")
        }
        val clippedEdges=clippingEvidence in setOf(ClippingEvidence.LIKELY_CLIPPED_LEFT,ClippingEvidence.LIKELY_CLIPPED_RIGHT,ClippingEvidence.LIKELY_CLIPPED_BOTH)
        val reasons=buildList {
            if(!axis)add("已按线条交点对齐轻微方向或透视变化，请核对覆盖位置。")
            if(groups.size>1)add("发现多个表格区域，请核对所选主表。")
            if(weakRows.isNotEmpty())add("已排除疑似文字形成的横线，请核对是否需要补充分割。")
            if(irregularHorizontalSpacing || irregularVerticalSpacing)add("候选边界间距不均匀，可能存在重复线或漏检边界，请核对结构。")
            if(horizontal.lines.size-hs.size>max(4,hs.size/3) || vertical.lines.size-vs.size>max(4,vs.size/3))add("图片中存在较多背景或界面干扰，请核对所选表格区域。")
            if(points.any { it.x<.003 || it.y<.003 || it.x>.997 || it.y>.997 })add("表格贴近图片边缘，请检查是否完整。")
            if(clippingEvidence==ClippingEvidence.POSSIBLE_CLIPPING)add("检测到边缘接触但证据不足，暂不推断缺失边界。")
            if(clippedEdges)add("检测到表格边界可能被裁切，已保留可见区域并要求补充核对。")
        }
        val recoveredXs=xs.toMutableList()
        val recoveredYs=ys.toMutableList()
        if(likelyRight)recoveredXs+=cw
        if(likelyLeft)recoveredXs.add(0,0)
        if(likelyBottom)recoveredYs+=ch
        if(likelyTop)recoveredYs.add(0,0)
        val grid=when {
            axis -> TableGrid(vs.map { (it.intercept*scale).roundToInt().coerceIn(0,original.width) },hs.map { (it.intercept*scale).roundToInt().coerceIn(0,original.height) },original.width,original.height,reasons=reasons,clippingEvidence=clippingEvidence)
            clippedEdges -> TableGrid(recoveredXs.distinct().sorted(),recoveredYs.distinct().sorted(),cw,ch,quad=quad,reasons=reasons,clippingEvidence=clippingEvidence)
            else -> TableGrid(xs,ys,cw,ch,quad=quad,reasons=reasons,clippingEvidence=clippingEvidence)
        }
        val diagnostics=StructureDiagnostics(
            geometryStages=stages.toList(),
            proposedQuad=quad,
            horizontalCandidateLines=horizontal.rawCandidateCount,
            verticalCandidateLines=vertical.rawCandidateCount,
            horizontalSelectedLines=hs.size,
            verticalSelectedLines=vs.size,
            horizontalMergedCandidateLines=horizontal.mergedCandidateCount,
            verticalMergedCandidateLines=vertical.mergedCandidateCount,
            clippingEvidence=clippingEvidence,
            clippingEvidenceReasons=clippingReasons,
            horizontalCandidates=diagnostics(horizontal,hs,w,h,true),
            verticalCandidates=diagnostics(vertical,vs,h,w,false),
        )
        return GeometryFrame(grid,diagnostics)
    }

    /** Projective parallel rulings form a family: their slopes vary linearly with position. */
    private fun coherent(lines: List<Line>,length: Int,breadth: Int): List<Line> {
        if(lines.size<4)return lines
        // Axis-aligned pages do not need a restrictive projective family. Preserve all
        // well-supported rulings and let the later intersection graph reject UI fragments.
        val nearAxis=lines.filter { abs(it.slope)<=.02f }
        if (nearAxis.size>=max(4, (lines.size*.55).roundToInt())) return nearAxis
        val medianSlope=lines.map { it.slope }.sorted()[lines.size/2]
        if (lines.count { abs(it.slope-medianSlope) < .012f } >= lines.size * .8 ||
            lines.count { it.evidence.normalizedLength>.75 && it.evidence.continuity>.8 } >= lines.size*.75) return lines
        val anchors=lines.sortedByDescending { it.evidence.score }.take(80)
        var best=lines;var bestScore=0.0
        for(i in anchors.indices)for(j in i+1 until anchors.size) {
            checkpoint()
            val a=anchors[i];val b=anchors[j]
            val pa=a.at(length/2f);val pb=b.at(length/2f)
            if(abs(pa-pb)<breadth*.2)continue
            val gradient=(b.slope-a.slope)/(pb-pa)
            if(abs(gradient)*breadth>.25)continue
            val matches=lines.filter { abs(it.slope-(a.slope+gradient*(it.at(length/2f)-pa)))<=.0085f }
            val score=matches.sumOf { it.evidence.score.pow(3) }
            if(matches.size>=3 && score>bestScore) { bestScore=score;best=matches }
        }
        return best
    }

    private fun lines(image: GrayImage,horizontal: Boolean): LineResult {
        val length=if(horizontal)image.width else image.height; val breadth=if(horizontal)image.height else image.width
        val points=mutableListOf<Pair<Int,Int>>()
        for(b in 0 until breadth)for(a in 0 until length)if(if(horizontal)image.dark(a,b) else image.dark(b,a))points+=a to b
        val minimum=length*(if(horizontal)policy.horizontalMinCoverage else policy.verticalMinCoverage)
        val continuityFloor=if(horizontal)policy.horizontalContinuityFloor else policy.verticalContinuityFloor
        val raw=mutableListOf<Line>()
        for(step in -12..12) {
            checkpoint()
            val slope=step*.01f; val shift=length/4+4; val votes=IntArray(breadth+shift*2)
            for((a,b)in points) { val i=(b-slope*a).roundToInt()+shift; if(i in votes.indices)votes[i]++ }
            for(i in 2 until votes.size-2) {
                if(votes[i]<minimum || votes[i]<votes[i-1] || votes[i]<=votes[i+1])continue
                val intercept=(i-shift).toFloat()
                val occupied=BooleanArray(length) { a -> val b=(slope*a+intercept).roundToInt(); b in 0 until breadth && (-1..1).any { d -> if(horizontal)image.dark(a,b+d) else image.dark(b+d,a) } }
                // Preserve long fragments on both sides of a gap, then discard remote toolbar
                // fragments. This is the old ruling evidence path; suppression happens after it.
                val hit=mutableListOf<Int>(); var start=0; var longest=0
                while(start<length) {
                    if(!occupied[start]) { start++; continue }
                    var end=start+1; while(end<length && occupied[end])end++
                    longest=max(longest,end-start); if(end-start>=12)for(a in start until end)hit+=a
                    start=end
                }
                if(hit.isEmpty())continue
                val clusters=mutableListOf<IntRange>(); var begin=0
                for(n in 1 until hit.size)if(hit[n]-hit[n-1]>max(10.0,length*.03)) {
                    clusters+=begin until n; begin=n
                }
                clusters+=begin until hit.size
                val largest=clusters.maxOf { it.last-it.first+1 }
                val substantial=clusters.filter { it.last-it.first+1>=largest*.25 }
                val last=substantial.last().last; val first=substantial.first().first
                hit.subList(last+1,hit.size).clear(); hit.subList(0,first).clear()
                val span=hit.last()-hit.first()+1
                val continuity=hit.size.toDouble()/span.coerceAtLeast(1)
                if(longest<max(24.0,minimum*.2) || hit.size<minimum || span<minimum || continuity<continuityFloor)continue
                val sampleStep=max(1,span/96); var contrastTotal=0.0; var contrastSamples=0
                for(a in hit.first()..hit.last() step sampleStep) {
                    val predicted=(slope*a+intercept).roundToInt(); val b=predicted.coerceIn(0,breadth-1)
                    val threshold=image.lineThreshold(if(horizontal)a else b,if(horizontal)b else a)
                    val value=if(horizontal)image.value(a,b) else image.value(b,a)
                    contrastTotal+=(threshold-value).coerceAtLeast(0).toDouble()/255.0; contrastSamples++
                }
                val localContrast=contrastTotal/contrastSamples.coerceAtLeast(1)
                val fragmentation=(clusters.size-1).toDouble()/max(1.0,span/32.0)
                val normalizedLength=span.toDouble()/length
                val texturePenalty=(fragmentation*.025).coerceAtMost(.12)
                val edgePenalty=if((horizontal && (hit.first()<length*.03 || hit.last()>length*.97)) || (!horizontal && (hit.first()<length*.03 || hit.last()>length*.97))) .015 else 0.0
                val score=(hit.size.toDouble()*(.94+.06*localContrast)*(1-texturePenalty-edgePenalty)).coerceAtLeast(0.0)
                raw+=Line(slope,intercept,hit.first(),hit.last(),LineEvidence(normalizedLength,continuity,localContrast,fragmentation,edgePenalty,score))
            }
        }
        val merged=merge(raw,length,horizontal).mapIndexed { index,line -> line.copy(candidateId=index) }
        val selected=mutableListOf<Line>()
        for(line in merged.sortedWith(compareByDescending<Line>{it.evidence.score}.thenBy {abs(it.slope)})) {
            checkpoint()
            if(selected.none {
                val begin=max(it.start,line.start).toFloat(); val end=min(it.end,line.end).toFloat()
                val sameFragment=end-begin>=min(it.end-it.start,line.end-line.start)*.8f &&
                    max(abs(it.at(begin)-line.at(begin)),abs(it.at(end)-line.at(end)))<5
                sameFragment || (abs(it.at(length/2f)-line.at(length/2f))<6 && abs(it.slope-line.slope)*length<20)
            })selected+=line
            if(selected.size>=600)break
        }
        val refined=selected.map { line ->
            checkpoint()
            if(abs(line.slope)<.001f)return@map line
            val samples=(line.start..line.end).mapNotNull { a ->
                val predicted=line.at(a.toFloat())
                val best=(-2..2).map { predicted.roundToInt()+it }.filter { it in 0 until breadth }
                    .minByOrNull { b -> (if(horizontal)image.value(a,b) else image.value(b,a))+abs(b-predicted)*4 } ?: return@mapNotNull null
                if(if(horizontal)image.dark(a,best) else image.dark(best,a))a.toDouble() to best.toDouble() else null
            }
            if(samples.size<24)return@map line
            val x=samples.map { it.first }.average(); val y=samples.map { it.second }.average()
            val denominator=samples.sumOf { (it.first-x).pow(2) }
            if(denominator<1)return@map line
            val slope=(samples.sumOf { (it.first-x)*(it.second-y) }/denominator).toFloat()
            line.copy(slope=slope,intercept=(y-slope*x).toFloat())
        }
        return LineResult(refined,raw.size,merged.size,merged)
    }

    private fun diagnostics(result: LineResult,selected: List<Line>,length: Int,breadth: Int,horizontal: Boolean): List<LineCandidateDiagnostic> {
        val selectedById=selected.associateBy { it.candidateId }
        val scoreFloor=selected.map { it.evidence.score }.sorted().let { scores -> if(scores.isEmpty())0.0 else scores[scores.size/2]*.65 }
        return result.candidates.map { candidate ->
            val line=selectedById[candidate.candidateId] ?: candidate
            val disposition=when {
                line.candidateId in selectedById -> CandidateDisposition.SELECTED
                line.evidence.score>=scoreFloor -> CandidateDisposition.REJECTED_HIGH_SCORE
                else -> CandidateDisposition.REJECTED_LOW_SCORE
            }
            LineCandidateDiagnostic(
                axis=if(horizontal)CandidateAxis.HORIZONTAL else CandidateAxis.VERTICAL,
                normalizedPosition=(line.at(length/2f)/breadth).coerceIn(-.25f,1.25f),
                normalizedStart=(line.start.toFloat()/length).coerceIn(0f,1f),
                normalizedEnd=(line.end.toFloat()/length).coerceIn(0f,1f),
                score=line.evidence.score,
                continuity=line.evidence.continuity,
                normalizedLength=line.evidence.normalizedLength,
                disposition=disposition,
                mergeGroupSize=line.mergeGroupSize,
                normalizedStartPosition=line.at(line.start.toFloat())/breadth,
                normalizedEndPosition=line.at(line.end.toFloat())/breadth,
            )
        }
    }

    private fun merge(candidates: List<Line>,length:Int,horizontal:Boolean): List<Line> {
        val tolerance=mergeTolerance(length,horizontal)
        val groups=mutableListOf<MutableList<Line>>()
        for(candidate in candidates.sortedByDescending { it.evidence.score }) {
            checkpoint()
            val group=groups.firstOrNull { existing -> equivalent(existing.first(),candidate,length,tolerance) }
            if(group==null)groups+=mutableListOf(candidate) else group+=candidate
        }
        return groups.map { group ->
            val best=group.maxBy { it.evidence.score }
            best.copy(start=group.minOf { it.start },end=group.maxOf { it.end },mergeGroupSize=group.size)
        }
    }

    private fun mergeTolerance(length:Int,horizontal:Boolean): Float {
        val fraction=if(horizontal)policy.horizontalMergeToleranceFraction else policy.verticalMergeToleranceFraction
        return max(2f,min(6.0,length*fraction).toFloat())
    }

    private fun equivalent(first:Line,second:Line,length:Int,tolerance:Float): Boolean {
        val begin=max(first.start,second.start).toFloat(); val end=min(first.end,second.end).toFloat()
        val overlapRatio=(end-begin)/min(first.end-first.start,second.end-second.start).toFloat().coerceAtLeast(1f)
        val centerDistance=abs(first.at(length/2f)-second.at(length/2f))
        val slopeDrift=abs(first.slope-second.slope)*length
        if(slopeDrift>max(8f,length*.012f))return false
        // Short or fragmented responses near a strong ruling are aliases of that ruling even
        // when their support intervals do not overlap. Long well-supported responses also
        // require overlap; this alone is not a guarantee of narrow-column recall.
        val strongPair=first.evidence.normalizedLength>.72 && second.evidence.normalizedLength>.72 &&
            first.evidence.continuity>.8 && second.evidence.continuity>.8
        return centerDistance<=tolerance && (overlapRatio>=.55 || !strongPair)
    }
}
