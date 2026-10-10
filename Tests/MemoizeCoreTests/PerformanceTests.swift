import Foundation
import Testing
@testable import MemoizeCore

@Test(.enabled(if:ProcessInfo.processInfo.environment["MEMOIZE_BENCHMARK"] == "1"))
func releaseBenchmarks() async throws {
    let store = try temporaryStore()
    let fixture = String(repeating:"A short paragraph with Markdown **emphasis**, `code`, and a few ordinary words.\n",count:20)
    let clock = ContinuousClock()
    let importStart = clock.now
    for index in 0..<10_000 {
        let note = RemoteMemo(name:"memos/bench-\(index)",content:"Note \(index) #work/macOS\n" + fixture + (index % 100 == 0 ? "uniqueneedle" : ""),createTime:Date(timeIntervalSince1970:Double(index)))
        try await store.reconcile(note)
    }
    print("BENCH import 10,000 × ~1.6KB notes: \(importStart.duration(to:clock.now))")
    var searches: [Double] = []
    for _ in 0..<30 {
        let start = clock.now; let notes = try await store.summaries(query:"uniqueneedle")
        #expect(notes.count == 100)
        let d = start.duration(to:clock.now).components; searches.append(Double(d.seconds)*1000 + Double(d.attoseconds)/1e15)
    }
    print("BENCH search p95 milliseconds: \(searches.sorted()[28])")
    for size in [10_000,100_000,1_000_000] {
        let text = String(repeating:fixture,count:size / fixture.utf8.count + 1)
        let start = clock.now; _ = MarkdownAnalysis.parse(text)
        print("BENCH parse \(text.utf8.count) bytes: \(start.duration(to:clock.now))")
    }
    let start = clock.now; let summaries = try await store.summaries()
    print("BENCH load \(summaries.count) summaries: \(start.duration(to:clock.now))")
}
