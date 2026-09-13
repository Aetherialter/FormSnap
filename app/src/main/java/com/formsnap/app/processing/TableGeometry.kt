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

/** Bounded geometric line voting with gap/slope tolerance. OCR never supplies separator locations. */
internal data class GeometryFrame(val grid: TableGrid,val diagnostics: StructureDiagnostics)

internal class LineGeometry(private val checkpoint: () -> Unit = {}) {
    private data class Line(val slope: Float,val intercept: Float,val start: Int,val end: Int,val score: Int) { fun at(v: Float)=slope*v+intercept }
    private data class LineResult(val lines: List<Line>,val candidateCount: Int)

    fun frame(original: GrayImage): GeometryFrame? {
        val scale=max(1,ceil(max(original.width,original.height)/1000.0).toInt()); val w=original.width/scale; val h=original.height/scale
        if(w<40 || h<40)return null
        val image=GrayImage(w,h,ByteArray(w*h) { n ->
            var v=255
            for(dy in 0 until scale)for(dx in 0 until scale)v=min(v,original.value(n%w*scale+dx,n/w*scale+dy))
            v.toByte()
        })
        val horizontal=lines(image,true);val vertical=lines(image,false)
        var hs=coherent(horizontal.lines,w,h);var vs=coherent(vertical.lines,h,w)
        fun cross(a: Line,b: Line): TablePoint { val x=(b.slope*a.intercept+b.intercept)/(1-b.slope*a.slope); return TablePoint(x,a.at(x)) }
        fun connected(a: Line,b: Line): Boolean { val p=cross(a,b); return p.x>=a.start-5 && p.x<=a.end+5 && p.y>=b.start-5 && p.y<=b.end+5 }
        // A sidebar can bridge otherwise separate regions with a handful of menu rules.
        // Table columns must participate in a substantial part of the recovered row family.
        // Do not apply the symmetric test to rows: merged header edges legitimately meet few columns.
        vs=vs.filter { b -> hs.count { connected(it,b) }>=max(3,ceil(hs.size*.45).toInt()) }
        repeat(2) { hs=hs.filter { a -> vs.count { connected(a,it) }>=2 }; vs=vs.filter { b -> hs.count { connected(it,b) }>=3 } }
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
        val weakRows=if(hs.count { it.score.toFloat()/(it.end-it.start+1)>.9 }>=hs.size*.8)
            hs.filter { it.score.toFloat()/(it.end-it.start+1)<.75 } else emptyList()
        hs=hs-weakRows.toSet()
        if(hs.size>501 || vs.size>129)return null
        val corners=listOf(cross(hs.first(),vs.first()),cross(hs.first(),vs.last()),cross(hs.last(),vs.last()),cross(hs.last(),vs.first()))
        if(corners.any { it.x< -w*.1 || it.y< -h*.1 || it.x>w*1.1 || it.y>h*1.1 })return null
        val points=corners.map { TablePoint((it.x*scale/original.width).coerceIn(0f,1f),(it.y*scale/original.height).coerceIn(0f,1f)) }
        val quad=TableQuad(points[0],points[1],points[2],points[3])
        val cw=(hypot(corners[1].x-corners[0].x,corners[1].y-corners[0].y)*scale).roundToInt()
        val ch=(hypot(corners[3].x-corners[0].x,corners[3].y-corners[0].y)*scale).roundToInt()
        if(cw<20 || ch<20)return null
        val mid=quad.map(.5f,.5f)
        val xs=vs.map { quad.inverse(TablePoint(it.at(mid.y*original.height/scale)*scale/original.width,mid.y)).x.times(cw).roundToInt().coerceIn(0,cw) }.distinct()
        val ys=hs.map { quad.inverse(TablePoint(mid.x,it.at(mid.x*original.width/scale)*scale/original.height)).y.times(ch).roundToInt().coerceIn(0,ch) }.distinct()
        if(xs.size<2 || ys.size<3 || xs.zipWithNext().any { it.second-it.first<4 } || ys.zipWithNext().any { it.second-it.first<4 })return null
        val axis=hs.all { abs(it.slope)<.001 } && vs.all { abs(it.slope)<.001 }
        // Only treat an edge as clipped when the recovered intersection itself is just
        // outside the image. A large margin around an ordinary perspective table is not
        // evidence of cropping and must not change the coordinate system.
        val clippedLeft=corners[0].x<0 || corners[3].x<0
        val clippedRight=corners[1].x> w || corners[2].x> w
        val clippedTop=corners[0].y<0 || corners[1].y<0
        val clippedBottom=corners[2].y>h || corners[3].y>h
        val clippedEdges=!axis && (clippedLeft || clippedRight || clippedTop || clippedBottom)
        val reasons=buildList {
            if(!axis)add("已按线条交点对齐轻微方向或透视变化，请核对覆盖位置。")
            if(groups.size>1)add("发现多个表格区域，请核对所选主表。")
            if(weakRows.isNotEmpty())add("已排除疑似文字形成的横线，请核对是否需要补充分割。")
            if(horizontal.lines.size-hs.size>max(4,hs.size/3) || vertical.lines.size-vs.size>max(4,vs.size/3))add("图片中存在较多背景或界面干扰，请核对所选表格区域。")
            if(points.any { it.x<.003 || it.y<.003 || it.x>.997 || it.y>.997 })add("表格贴近图片边缘，请检查是否完整。")
            if(clippedEdges)add("检测到表格边界可能被裁切，已保留可见区域并要求补充核对。")
        }
        val recoveredXs=xs.toMutableList()
        val recoveredYs=ys.toMutableList()
        if(clippedRight)recoveredXs+=cw
        if(clippedLeft)recoveredXs.add(0,0)
        if(clippedBottom)recoveredYs+=ch
        if(clippedTop)recoveredYs.add(0,0)
        val grid=when {
            axis -> TableGrid(vs.map { (it.intercept*scale).roundToInt().coerceIn(0,original.width) },hs.map { (it.intercept*scale).roundToInt().coerceIn(0,original.height) },original.width,original.height,reasons=reasons)
            clippedEdges -> TableGrid(recoveredXs.distinct().sorted(),recoveredYs.distinct().sorted(),cw,ch,quad=quad,reasons=reasons)
            else -> TableGrid(xs,ys,cw,ch,quad=quad,reasons=reasons)
        }
        return GeometryFrame(grid,StructureDiagnostics(horizontal.candidateCount,vertical.candidateCount,hs.size,vs.size))
    }

    /** Projective parallel rulings form a family: their slopes vary linearly with position.
     * Select the family supported by long lines; this tolerates perspective without accepting
     * arbitrary screen texture directions or forcing every page to one rotation angle. */
    private fun coherent(lines: List<Line>,length: Int,breadth: Int): List<Line> {
        if(lines.size<4)return lines
        val anchors=lines.sortedByDescending { it.score }.take(80)
        var best=lines;var bestScore=0.0
        for(i in anchors.indices)for(j in i+1 until anchors.size) {
            checkpoint()
            val a=anchors[i];val b=anchors[j]
            val pa=a.at(length/2f);val pb=b.at(length/2f)
            if(abs(pa-pb)<breadth*.2)continue
            val gradient=(b.slope-a.slope)/(pb-pa)
            if(abs(gradient)*breadth>.25)continue
            val matches=lines.filter { abs(it.slope-(a.slope+gradient*(it.at(length/2f)-pa)))<=.0085f }
            val score=matches.sumOf { it.score.toDouble().pow(3) }
            if(matches.size>=3 && score>bestScore) { bestScore=score;best=matches }
        }
        return best
    }

    private fun lines(image: GrayImage,horizontal: Boolean): LineResult {
        val length=if(horizontal)image.width else image.height; val breadth=if(horizontal)image.height else image.width
        val points=mutableListOf<Pair<Int,Int>>()
        for(b in 0 until breadth)for(a in 0 until length)if(if(horizontal)image.dark(a,b) else image.dark(b,a))points+=a to b
        val candidates=mutableListOf<Line>(); val minimum=length*(if(horizontal).24 else .14)
        for(step in -12..12) {
            checkpoint()
            val slope=step*.01f; val shift=length/4+4; val votes=IntArray(breadth+shift*2)
            for((a,b)in points) { val i=(b-slope*a).roundToInt()+shift; if(i in votes.indices)votes[i]++ }
            for(i in 2 until votes.size-2) {
                if(votes[i]<minimum || votes[i]<votes[i-1] || votes[i]<=votes[i+1])continue
                val intercept=(i-shift).toFloat()
                val occupied=BooleanArray(length) { a -> val b=(slope*a+intercept).roundToInt(); b in 0 until breadth && (-1..1).any { d -> if(horizontal)image.dark(a,b+d) else image.dark(b+d,a) } }
                // Isolated aligned glyph strokes are not line evidence. Preserve long fragments,
                // including either side of a gap, before measuring coverage or ranking directions.
                val hit=mutableListOf<Int>()
                var start=0
                var longest=0
                while(start<length) {
                    if(!occupied[start]) { start++; continue }
                    var end=start+1
                    while(end<length && occupied[end])end++
                    longest=max(longest,end-start)
                    if(end-start>=12)for(a in start until end)hit+=a
                    start=end
                }
                // Ignore small, remote fragments (toolbar text/taskbar icons) that would otherwise
                // extend a genuine ruling through unrelated parts of a photographed screen.
                if(hit.isNotEmpty()) {
                    val clusters=mutableListOf<IntRange>();var begin=0
                    for(n in 1 until hit.size)if(hit[n]-hit[n-1]>max(10.0,length*.03)) {
                        clusters+=begin until n;begin=n
                    }
                    clusters+=begin until hit.size
                    val largest=clusters.maxOf { it.last-it.first+1 }
                    val substantial=clusters.filter { it.last-it.first+1>=largest*.25 }
                    val last=substantial.last().last;val first=substantial.first().first
                    hit.subList(last+1,hit.size).clear()
                    hit.subList(0,first).clear()
                }
                if(longest<max(24.0,minimum*.2) || hit.size<minimum || hit.last()-hit.first()<minimum || hit.size.toDouble()/(hit.last()-hit.first()+1)<.55)continue
                candidates+=Line(slope,intercept,hit.first(),hit.last(),hit.size)
            }
        }
        val selected=mutableListOf<Line>()
        for(line in candidates.sortedWith(compareByDescending<Line>{it.score}.thenBy {abs(it.slope)})) {
            checkpoint()
            if(selected.none {
                val begin=max(it.start,line.start).toFloat();val end=min(it.end,line.end).toFloat()
                val sameFragment=end-begin>=min(it.end-it.start,line.end-line.start)*.8f &&
                    max(abs(it.at(begin)-line.at(begin)),abs(it.at(end)-line.at(end)))<5
                sameFragment || (abs(it.at(length/2f)-line.at(length/2f))<6 && abs(it.slope-line.slope)*length<20)
            })selected+=line
            if(selected.size>=600)break
        }
        val refined=selected.map { line ->
            checkpoint()
            if(abs(line.slope)<.001f)return@map line
            // Refine the coarse 0.01 slope vote using the nearest dark stroke. A rounding step
            // must not turn into a multi-pixel drift across a long photographed column.
            val samples=(line.start..line.end).mapNotNull { a ->
                val predicted=line.at(a.toFloat())
                val best=(-2..2).map { predicted.roundToInt()+it }.filter { it in 0 until breadth }
                    .minByOrNull { b -> (if(horizontal)image.value(a,b) else image.value(b,a))+abs(b-predicted)*4 } ?: return@mapNotNull null
                if(if(horizontal)image.dark(a,best) else image.dark(best,a))a.toDouble() to best.toDouble() else null
            }
            if(samples.size<24)return@map line
            val x=samples.map { it.first }.average();val y=samples.map { it.second }.average()
            val denominator=samples.sumOf { (it.first-x).pow(2) }
            if(denominator<1)return@map line
            val slope=(samples.sumOf { (it.first-x)*(it.second-y) }/denominator).toFloat()
            line.copy(slope=slope,intercept=(y-slope*x).toFloat())
        }
        return LineResult(refined,candidates.size)
    }
}
