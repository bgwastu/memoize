import AppKit
let destination = URL(fileURLWithPath:CommandLine.arguments[1],isDirectory:true)
try FileManager.default.createDirectory(at:destination,withIntermediateDirectories:true)
for size in [16,32,128,256,512] {
    for scale in [1,2] {
        let pixels = size * scale
        let image = NSImage(size:NSSize(width:1024,height:1024),flipped:false) { _ in
            let rect = NSRect(x:64,y:64,width:896,height:896)
            let shape = NSBezierPath(roundedRect:rect,xRadius:210,yRadius:210)
            NSGradient(starting:NSColor(calibratedRed:0.34,green:0.38,blue:0.62,alpha:1),ending:NSColor(calibratedRed:0.19,green:0.23,blue:0.40,alpha:1))!.draw(in:shape,angle:270)
            let paper = NSBezierPath(roundedRect:NSRect(x:258,y:208,width:508,height:608),xRadius:42,yRadius:42)
            NSColor(calibratedWhite:0.99,alpha:0.95).setFill(); paper.fill()
            NSColor(calibratedRed:0.33,green:0.38,blue:0.61,alpha:0.8).setStroke()
            for (y,width) in [(654.0,282.0),(525.0,282.0),(396.0,172.0)] {
                let line = NSBezierPath(); line.lineWidth = 32; line.lineCapStyle = .round; line.move(to:NSPoint(x:358,y:y)); line.line(to:NSPoint(x:358 + width,y:y)); line.stroke()
            }
            return true
        }
        let bitmap = NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:pixels,pixelsHigh:pixels,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
        NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep:bitmap)
        image.draw(in:NSRect(x:0,y:0,width:pixels,height:pixels)); NSGraphicsContext.restoreGraphicsState()
        try bitmap.representation(using:.png,properties:[:])!.write(to:destination.appending(path:"icon_\(size)x\(size)\(scale == 2 ? "@2x" : "").png"))
    }
}
