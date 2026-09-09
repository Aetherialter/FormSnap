package com.formsnap.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.formsnap.app.data.source.SourceImageLoader
import com.formsnap.app.domain.model.SourceDocument
import com.formsnap.app.processing.*
import kotlinx.coroutines.CancellationException
import kotlin.math.min

@Composable
fun StructureScreen(model: StructureViewModel,sources: List<SourceDocument>,images: SourceImageLoader,onBack: ()->Unit,onFields: ()->Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val saving by model.busy.collectAsStateWithLifecycle()
    val activity by model.activity.collectAsStateWithLifecycle()
    val busy=saving || activity?.busy!=false
    val notice by model.notice.collectAsStateWithLifecycle()
    val selected by model.selected.collectAsStateWithLifecycle()
    var replace by rememberSaveable { mutableStateOf(false) }
    Page("确认表格结构",onBack) {
        Text("先核对表头和行列，再整理内容。同模板后续页面将优先复用本次确认。")
        notice?.let { Text(it,Modifier.padding(vertical=8.dp),color=MaterialTheme.colorScheme.primary) }
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(activity==null)TextButton(onClick=model::retry){Text("重新读取后台状态")}
        when(val current=state) {
            StructureUiState.Loading -> Text("正在读取表格结构…")
            StructureUiState.Error -> { Text("结构暂时无法读取。"); TextButton(onClick=model::retry){Text("重试")} }
            is StructureUiState.Ready -> {
                if(current.pages.isEmpty())Text("尚无可确认结构，请先添加页面并开始整理。")
                else {
                    val page=current.pages.firstOrNull { it.proposal.sourceId==selected } ?: current.pages.firstOrNull { !it.confirmed } ?: current.pages.first()
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        current.pages.forEach { item -> TextButton(onClick={model.select(item.proposal.sourceId)},enabled=!busy) {
                            val number=sources.firstOrNull { it.id==item.proposal.sourceId }?.pageIndex?.plus(1)
                            Text("${if(number==null)"来源信息待恢复" else "第${number}页"}${if(item.confirmed)" · 已确认" else " · 待确认"}")
                        } }
                    }
                    key(page.proposal.sourceId,page.revision) {
                        val initial=remember(page){StructureEdits.from(page.proposal)}
                        var columns by rememberSaveable { mutableStateOf(initial.columns) }
                        var rows by rememberSaveable { mutableStateOf(initial.rows) }
                        var corners by rememberSaveable { mutableStateOf(initial.corners) }
                        var merges by rememberSaveable { mutableStateOf(initial.merges) }
                        var start by rememberSaveable { mutableStateOf(initial.start) }
                        var end by rememberSaveable { mutableStateOf(initial.end) }
                        var paths by rememberSaveable { mutableStateOf(initial.paths) }
                        var error by rememberSaveable { mutableStateOf<String?>(null) }
                        var allowReplace by rememberSaveable { mutableStateOf(false) }
                        val edits=StructureEdits(columns,rows,corners,merges,start,end,paths)
                        val parsed=runCatching { edits.grid() }
                        val grid=parsed.getOrNull() ?: page.proposal.grid
                        Text("${grid.columns}列 · ${grid.rows}行（含表头） · ${grid.merged.size}个合并区域",style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("structureSummary"))
                        page.proposal.grid.reasons.forEach { Text("• $it",style=MaterialTheme.typography.bodySmall) }
                        StructureOverlay(sources.singleOrNull { it.id==page.proposal.sourceId },images,grid)
                        if(parsed.isFailure)Text("输入尚不完整，覆盖图暂显示已保存结构。",color=MaterialTheme.colorScheme.error)
                        Text("蓝色：表头；橙色：合并。双指缩放查看，修改后覆盖图即时更新。",style=MaterialTheme.typography.bodySmall)
                        HorizontalDivider(Modifier.padding(vertical=12.dp))
                        StructureInput("表格四角位置（百分比）",corners,{corners=it;paths=""},!busy,"structureCorners",
                            "按左上、右上、右下、左下填写 x,y；用分号分隔。可修正区域与轻度透视。")
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(start,{start=it;paths=""},label={Text("表头首行")},enabled=!busy,modifier=Modifier.weight(1f).testTag("headerStart"))
                            OutlinedTextField(end,{end=it;paths=""},label={Text("表头末行")},enabled=!busy,modifier=Modifier.weight(1f).testTag("headerEnd"))
                        }
                        StructureInput("列分割位置（0–100）",columns,{columns=it;paths=""},!busy,"columnCuts","按从左至右的百分比排列。加入数值可增加分割，移除数值可删除分割；保留两端0和100。")
                        StructureInput("行分割位置（0–100）",rows,{rows=it;paths=""},!busy,"rowCuts","按从上至下排列。增删后请同步核对表头与合并区域的行列编号。")
                        StructureInput("合并区域",merges,{merges=it;paths=""},!busy,"mergedRegions","每项：起始行,起始列,跨行数,跨列数（从1开始）；分号分隔。清空某项可拆分该区域。")
                        StructureInput("字段路径（每列一行）",paths,{paths=it},!busy,"headerPaths","层级使用 / 分隔。调整几何后留空会重新读取表头；必要时可修正文字。")
                        Text("数据区合并只在左上格保留识别内容，其他覆盖格不猜值；这些值会进入后续复核。",style=MaterialTheme.typography.bodySmall)
                        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                        fun validate(action: (TableGrid,List<List<String>>)->Unit) {
                            val result=runCatching { edits.grid() to edits.headerPaths() }
                            if(result.isFailure)error="请检查坐标、分割顺序和合并范围；四角不能交叉，表头后须有数据行。"
                            else { error=null; action(result.getOrThrow().first,result.getOrThrow().second) }
                        }
                        OutlinedButton(onClick={validate { g,p -> model.save(page,g,p) }},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("saveStructureDraft")){Text("应用调整并保存草稿")}
                        Row { Checkbox(allowReplace,{allowReplace=it},enabled=!busy);Text("允许替换此页已有人工结果（需要再次确认）") }
                        Button(onClick={validate { g,p -> if(page.confirmed || allowReplace)replace=true else model.confirm(page,g,p,false) }},enabled=!busy,
                            modifier=Modifier.fillMaxWidth().testTag("confirmStructure")){Text("确认结构并继续")}
                        TextButton(onClick=onFields){Text("查看字段设置")}
                        if(replace)AlertDialog(onDismissRequest={replace=false},title={Text("替换此页整理结果？")},
                            text={Text("重新确认会替换此页数据，包括人工修改；原图保留。其他页面的已保存数据不变。")},
                            confirmButton={TextButton(onClick={replace=false;validate { g,p -> model.confirm(page,g,p,true) }},enabled=!busy){Text("确认替换")}},
                            dismissButton={TextButton(onClick={replace=false}){Text("取消")}})
                    }
                }
            }
        }
    }
}

@Composable
private fun StructureInput(label: String,value: String,change: (String)->Unit,enabled: Boolean,tag: String,hint: String) {
    OutlinedTextField(value,change,label={Text(label)},supportingText={Text(hint)},enabled=enabled,modifier=Modifier.fillMaxWidth().padding(vertical=5.dp).testTag(tag))
}

@Composable
private fun StructureOverlay(source: SourceDocument?,images: SourceImageLoader,grid: TableGrid) {
    var failed by remember(source?.sourceUri) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null,source?.sourceUri) {
        value=try { source?.let { images.load(it.sourceUri,1600) } }
        catch(cancelled: CancellationException){throw cancelled}
        catch(_: Exception){failed=true;null}
    }
    var scale by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
    val transform=rememberTransformableState { _,zoom,offset,_ -> scale=(scale*zoom).coerceIn(1f,6f);pan=if(scale==1f)Offset.Zero else pan+offset }
    val image=bitmap
    // Reserve the same space while decoding: an arriving image must not move the save/confirm
    // buttons between scrolling to them and tapping them, including after Activity recreation.
    Box(Modifier.fillMaxWidth().height(340.dp).graphicsLayer { clip=true }.background(Color(0xFFF2F4F4))) {
        if(image==null)Text(if(source==null)"来源页信息暂不可用，请返回来源页重试。" else if(failed)"原图暂不可用，请返回来源页检查。" else "正在读取原纸…")
        else {
            Canvas(Modifier.fillMaxSize().graphicsLayer { scaleX=scale;scaleY=scale;translationX=pan.x;translationY=pan.y }.transformable(transform)
                .semantics { contentDescription="原图与表格结构覆盖：${grid.columns}列，${grid.rows}行" }.testTag("structureOverlay")) {
                val ratio=min(size.width/image.width,size.height/image.height)
                val w=image.width*ratio;val h=image.height*ratio;val origin=Offset((size.width-w)/2,(size.height-h)/2)
                drawImage(image.asImageBitmap(),dstOffset=IntOffset(origin.x.toInt(),origin.y.toInt()),dstSize=IntSize(w.toInt(),h.toInt()))
                fun path(cell: GridCell): Path=Path().apply { grid.corners(cell).forEachIndexed { index,p ->
                    val x=origin.x+p.x*w;val y=origin.y+p.y*h;if(index==0)moveTo(x,y)else lineTo(x,y)
                };close() }
                drawPath(path(GridCell(grid.headerStart,0,grid.headerEnd-grid.headerStart,grid.columns)),Color(0x332177CC))
                grid.cells.forEach { drawPath(path(it),if(it.rowSpan>1 || it.colSpan>1)Color(0xFFFF8A00) else Color(0xFF168B83),style=Stroke(1.5f)) }
                drawPath(path(GridCell(0,0,grid.rows,grid.columns)),Color(0xFF123C47),style=Stroke(3f))
            }
        }
    }
    TextButton(onClick={scale=1f;pan=Offset.Zero},enabled=image!=null){Text("复位视图")}
}
