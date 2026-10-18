# Memory Profiler Implementation Notes

Date: 2026-10-16

Baseline: `main`, `9d556296f66138738fad3626a45c81a07469fe68`.
Existing GPU, shared icon, progress, and layout-map changes were outside this task.

## Implemented Surface

- The default Java heap view is Classes, with vertical filters, a virtualized class table,
  an instance list, and a separate object inspector.
- Classes exposes counts, shallow size, retained size, available native size, and recorded heaps.
  Class/package grouping, heap/scope/leak filters, regex/case matching, sorting, and saved filters
  reuse the existing controller and presenter.
- The inspector separates fields/outbound targets, inbound references, and the GC Root path into
  tabs. Target navigation, back/forward, copy ID, pinning, and absolute array ranges remain wired
  to the active snapshot's query.
- Compact snapshot tabs retain source/count/time/capability metadata in tooltips and accessibility
  semantics. Closing a snapshot removes its workspace state without deleting the source file.
- The workspace offers Import, Mapping, Compare, and investigation exports. Existing heap capture,
  bitmap capture, native capture, file-menu imports/exports, and LeakCanary remain available.
- Snapshot summary and heap distribution replace the reference image's live heap/GC timeline.
  With no recorded heap partitions, the distribution uses shallow sizes of the largest classes.
  Retained sizes are not summed into a partition chart because retained sets can overlap.
- Dominator rows use depth-first tree order, collapsed children, and direct object navigation.
  Dominators and Diff expose class search; heap/scope/leak filters are specific to Classes.
- Narrow windows retain bounded panes with horizontal scrolling. Automated captures cover
  1440x860 English and 1280x860 Simplified Chinese, in light and dark themes.

## Evidence Contracts

- HPROF does not provide allocation/deallocation events. Histogram construction no longer fills
  allocation fields from live-object counts. Missing metrics remain unavailable.
- Native histogram totals are not counted a second time from bitmap evidence, and unscoped bitmap
  totals are not substituted into a heap-filtered class table.
- Diff accepts two distinct loaded snapshots. Its result records both source identities and
  mappings, and exposes A/B counts, count delta, A/B retained, retained delta, and shallow delta.
  Matching is class-name based, not object identity or ClassLoader identity.
- Retained-only changes are included. Unknown retained values remain unknown; absent classes have
  a zero contribution. Switching tabs does not silently change the selected comparison pair.
- Diff CSV adds retained values, both snapshot IDs, source/mapping hashes, algorithm version, and
  the comparison limitation. Object JSON and snapshot JSON include provenance and limitations;
  investigation reports include both comparison sources. Raw HPROF exports remain byte copies.
- Query indexes and dominator rows are constructed with `LoadedHeap` on the backend dispatcher
  and reused across tab switches. Snapshot navigation is blocked during active loading.

## Verification Boundary

Focused tests cover parser, analysis, controller, presentation, and export behavior, including
cancelled imports preserving the published snapshot and object IDs not surviving a dump change.
Compose captures are automated rendering evidence, not native-window or real-device acceptance.
Large real HPROF responsiveness, device capture, packaged application behavior, and native GUI
acceptance still require separate runtime validation.
