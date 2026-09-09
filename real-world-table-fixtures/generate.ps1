# Offline, invented business forms. Windows fixture authoring only; not an app dependency.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
function Write-Fixture($name, [int[]]$xs, [int[]]$ys, $depth, $merged, $gap) {
    $width = 2400; $height = 1500
    $bitmap = [System.Drawing.Bitmap]::new($width, $height)
    $canvas = [System.Drawing.Graphics]::FromImage($bitmap)
    $canvas.Clear([System.Drawing.Color]::White)
    $canvas.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $font = [System.Drawing.Font]::new('Microsoft YaHei', 20, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $titleFont = [System.Drawing.Font]::new('Microsoft YaHei', 32, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
    $pen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(170,170,170), 3)
    $texture = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(238,238,238), 1)
    for ($y=0; $y -lt $height; $y+=7) { $canvas.DrawLine($texture,0,$y,$width,$y) }
    $canvas.DrawString('业务记录汇总表（完全虚构的验收样本）', $titleFont, [System.Drawing.Brushes]::Black, 120, 60)
    $canvas.DrawString('机构：示例单位    批次：秋季    说明：空白表示未填写，所有内容均为测试数据。', $font, [System.Drawing.Brushes]::Black, 120, 130)
    $words = [System.Collections.Generic.List[object]]::new()
    $rows=$ys.Count-1; $columns=$xs.Count-1
    for ($r=0; $r -lt $rows; $r++) { for ($c=0; $c -lt $columns; $c++) {
        $merge = @($merged | Where-Object { $r -ge $_.row -and $r -lt ($_.row+$_.rowSpan) -and $c -ge $_.column -and $c -lt ($_.column+$_.colSpan) })
        if ($merge.Count -gt 0 -and ($r -ne $merge[0].row -or $c -ne $merge[0].column)) { continue }
        $rs=1; $cs=1
        if ($merge.Count -gt 0) { $rs=$merge[0].rowSpan; $cs=$merge[0].colSpan }
        $rect=[System.Drawing.RectangleF]::new($xs[$c],$ys[$r],($xs[$c+$cs]-$xs[$c]),($ys[$r+$rs]-$ys[$r]))
        $canvas.DrawRectangle($pen,$rect.X,$rect.Y,$rect.Width,$rect.Height)
        if ($r -ge $depth -and $c -gt 0 -and ($r+$c)%3 -ne 0) { continue }
        if ($r -lt $depth) { $value=@('业务类别','统计分组','项目名称')[$r%3] }
        else { $value=[string]($r*100+$c) }
        if ($columns -gt 20 -and $r -lt $depth -and $cs -eq 1) { $value=$value.Substring(0,2)+"`n"+$value.Substring(2) }
        $measured=$canvas.MeasureString($value,$font)
        $left=$rect.X+($rect.Width-$measured.Width)/2
        $top=$rect.Y+($rect.Height-$measured.Height)/2
        $canvas.DrawString($value,$font,[System.Drawing.Brushes]::Black,$left,$top)
        $words.Add(@{text=$value;left=$left/$width;top=$top/$height;right=($left+$measured.Width)/$width;bottom=($top+$measured.Height)/$height;row=$r;column=$c})
    } }
    if ($gap) { $canvas.FillRectangle([System.Drawing.Brushes]::White,($xs[2]-3),($ys[4]+18),6,30) }
    $bitmap.Save((Join-Path $PSScriptRoot "$name.png"),[System.Drawing.Imaging.ImageFormat]::Png)
    @{name=$name;origin='Artificial printed Chinese form, not a photographed page or real OCR result';width=$width;height=$height;tableRegion=@($xs[0],$ys[0],$xs[-1],$ys[-1]);headerRows=$depth;rows=$rows;columns=$columns;merged=@($merged);expectedOutcome= $(if ($depth -gt 1 -or $gap) {'STRUCTURE_REVIEW_REQUIRED'} else {'AUTO_ACCEPTED'});textEvidence=@($words)} | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $PSScriptRoot "$name.json") -Encoding utf8
    $texture.Dispose(); $pen.Dispose(); $font.Dispose(); $titleFont.Dispose(); $canvas.Dispose(); $bitmap.Dispose()
}
Write-Fixture 'printed-regular' @(120,460,800,1140,1480,1820,2160) @(350,470,590,710,830,950,1070,1190) 1 @() $false
$merges=@()
for ($c=0; $c -lt 36; $c+=6) { $merges+=@{row=0;column=$c;rowSpan=1;colSpan=6} }
for ($c=0; $c -lt 36; $c+=3) { $merges+=@{row=1;column=$c;rowSpan=1;colSpan=3} }
Write-Fixture 'printed-36-column-header' @((0..36 | ForEach-Object {120+$_*60})) @(350,450,550,650,750,850,950,1050,1150,1250,1350) 3 $merges $false
Write-Fixture 'printed-gaps-variable-width' @(120,250,500,1000,1180,1700,2280) @(500,610,720,830,940,1050,1160,1270,1380) 1 @() $true
