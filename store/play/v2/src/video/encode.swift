// Encodes the how-to video: each UI state is a still, with finger taps / holds / swipes animated on top
// and short cross-fades between states.  usage: encode <timeline.json> <out.mp4>
import AVFoundation
import CoreGraphics
import CoreVideo
import Foundation
import ImageIO

struct Ev { let type: String; let x: Double; let y: Double; let x2: Double; let y2: Double; let at: Double }
struct Scene { let img: CGImage; let dur: Double; let events: [Ev] }

let args = CommandLine.arguments
guard args.count == 3 else { FileHandle.standardError.write("usage: encode timeline.json out.mp4\n".data(using: .utf8)!); exit(1) }
let json = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: args[1]))) as! [[String: Any]]
let scenes: [Scene] = json.map { d in
    let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: d["png"] as! String) as CFURL, nil)!
    let evs = (d["events"] as! [[String: Any]]).map { e in
        Ev(type: e["type"] as! String, x: e["x"] as! Double, y: e["y"] as! Double,
           x2: (e["x2"] as? Double) ?? (e["x"] as! Double), y2: (e["y2"] as? Double) ?? (e["y"] as! Double), at: e["at"] as! Double)
    }
    return Scene(img: CGImageSourceCreateImageAtIndex(src, 0, nil)!, dur: d["dur"] as! Double, events: evs)
}
let W = 1920, H = 1080, fps = 30, fade = 0.3
let starts: [Double] = scenes.indices.map { i in scenes[..<i].reduce(0) { $0 + $1.dur } }
let total = scenes.reduce(0) { $0 + $1.dur }
let frames = Int(total * Double(fps))

let outURL = URL(fileURLWithPath: args[2]); try? FileManager.default.removeItem(at: outURL)
let writer = try AVAssetWriter(outputURL: outURL, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: W, AVVideoHeightKey: H,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 5_000_000, AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel]])
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
    kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA, kCVPixelBufferWidthKey as String: W, kCVPixelBufferHeightKey as String: H])
writer.add(input); writer.startWriting(); writer.startSession(atSourceTime: .zero)

func smooth(_ x: Double) -> Double { let t = min(max(x, 0), 1); return t * t * (3 - 2 * t) }

func ring(_ ctx: CGContext, _ x: Double, _ y: Double, r: Double, a: Double, width: Double) {
    ctx.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: a)); ctx.setLineWidth(width)
    ctx.strokeEllipse(in: CGRect(x: x - r, y: Double(H) - y - r, width: 2 * r, height: 2 * r))
}
func dot(_ ctx: CGContext, _ x: Double, _ y: Double, r: Double, a: Double) {
    ctx.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: a))
    ctx.fillEllipse(in: CGRect(x: x - r, y: Double(H) - y - r, width: 2 * r, height: 2 * r))
}
func drawEvents(_ ctx: CGContext, _ evs: [Ev], _ t: Double) {
    for e in evs {
        let p = t - e.at
        switch e.type {
        case "tap":
            if p > -0.35 && p < 0.9 {
                // finger approaches (grows in), presses, ripple fades
                let appear = smooth((p + 0.35) / 0.35)
                dot(ctx, e.x, e.y, r: 20 - 4 * smooth(p / 0.12), a: 0.55 * appear * (1 - smooth((p - 0.2) / 0.5)))
                if p > 0 { let q = min(p / 0.7, 1); ring(ctx, e.x, e.y, r: 24 + 52 * q, a: 0.85 * (1 - q), width: 5) }
            }
        case "hold":
            if p > -0.35 && p < 1.4 {
                let appear = smooth((p + 0.35) / 0.35)
                dot(ctx, e.x, e.y, r: 18, a: 0.6 * appear * (1 - smooth((p - 1.0) / 0.4)))
                if p > 0 { for k in 0..<2 { let q = ((p * 0.9) + Double(k) * 0.5).truncatingRemainder(dividingBy: 1); ring(ctx, e.x, e.y, r: 24 + 46 * q, a: 0.7 * (1 - q), width: 4) } }
            }
        case "swipe":
            if p > -0.3 && p < 1.0 {
                let appear = smooth((p + 0.3) / 0.3), move = smooth(p / 0.6)
                let x = e.x + (e.x2 - e.x) * move, y = e.y + (e.y2 - e.y) * move
                if p > 0 { for k in 1...5 { let m = max(move - Double(k) * 0.06, 0); dot(ctx, e.x + (e.x2 - e.x) * m, e.y + (e.y2 - e.y) * m, r: 20 - Double(k) * 2.5, a: 0.18) } }
                dot(ctx, x, y, r: 22, a: 0.6 * appear * (1 - smooth((p - 0.7) / 0.3)))
            }
        default: break
        }
    }
}

for f in 0..<frames {
    let t = Double(f) / Double(fps)
    var idx = scenes.count - 1
    for i in scenes.indices where t < starts[i] + scenes[i].dur { idx = i; break }
    let local = t - starts[idx]
    var pb: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &pb)
    let buf = pb!
    CVPixelBufferLockBaseAddress(buf, [])
    let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buf), width: W, height: H, bitsPerComponent: 8,
                        bytesPerRow: CVPixelBufferGetBytesPerRow(buf), space: CGColorSpaceCreateDeviceRGB(),
                        bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)!
    let full = CGRect(x: 0, y: 0, width: W, height: H)
    ctx.setFillColor(CGColor(red: 0.04, green: 0.055, blue: 0.1, alpha: 1)); ctx.fill(full)
    ctx.draw(scenes[idx].img, in: full)
    let toEnd = scenes[idx].dur - local
    if idx + 1 < scenes.count && toEnd < fade {
        ctx.setAlpha(CGFloat(smooth(1 - toEnd / fade))); ctx.draw(scenes[idx + 1].img, in: full); ctx.setAlpha(1)
    } else {
        drawEvents(ctx, scenes[idx].events, local)
    }
    var master = 1.0
    if t < 0.4 { master = smooth(t / 0.4) }
    if t > total - 0.5 { master = smooth((total - t) / 0.5) }
    if master < 1 { ctx.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: CGFloat(1 - master))); ctx.fill(full) }
    CVPixelBufferUnlockBaseAddress(buf, [])
    while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.002) }
    adaptor.append(buf, withPresentationTime: CMTime(value: CMTimeValue(f), timescale: CMTimeScale(fps)))
}
input.markAsFinished()
let sem = DispatchSemaphore(value: 0); writer.finishWriting { sem.signal() }; sem.wait()
print(writer.status == .completed ? "wrote \(outURL.path) (\(Int(total))s)" : "failed: \(String(describing: writer.error))")
