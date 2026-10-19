## ADDED Requirements

### Requirement: Analysis modules support live drag-to-reorder
The home screen's analysis-module grid SHALL let the user reorder module cards by long-pressing a card and dragging it. While the drag is in progress, the other cards SHALL reposition in real time to make room for the dragged card (phone-style), not only on drop.

#### Scenario: Live reorder during drag
- **WHEN** the user long-presses a module card and drags it across another card's slot
- **THEN** the displaced cards animate aside immediately as the dragged card passes, before the user releases

#### Scenario: Drop commits the new order
- **WHEN** the user releases the dragged card over a slot
- **THEN** the card settles into that slot's position and the resulting order becomes the active module order

#### Scenario: Long-press does not open the module
- **WHEN** the user long-presses a card, drags, and releases without having crossed into another slot
- **THEN** the module SHALL NOT open and the order SHALL be unchanged

#### Scenario: Tap still opens the module
- **WHEN** the user taps a module card without a long-press drag gesture
- **THEN** the module opens as before

### Requirement: Module order is persisted across launches
The active module order SHALL be persisted to user settings and restored on the next launch.

#### Scenario: Module order persists
- **WHEN** the user reorders modules and restarts the app
- **THEN** the module grid and navigation rail restore the user's saved module order

### Requirement: Navigation rail follows the module order
The navigation rail's module entries SHALL be rendered in the persisted module order, with Home pinned as the first entry. Reordering modules on the home screen SHALL reorder the rail.

#### Scenario: Rail reflects home reorder
- **WHEN** the user reorders modules on the home screen
- **THEN** the navigation rail entries (excluding the pinned Home entry) appear in the new order

#### Scenario: Home stays pinned in the rail
- **WHEN** the module order is changed in any way
- **THEN** Home remains the first navigation-rail entry and is never reordered by module customization

### Requirement: Defaults preserve current behavior
On first launch with no persisted layout, the module order SHALL equal the current hardcoded order (Layout Inspector, Simpleperf, Perfetto, Memory Profiler, Frame Profiler, Startup Profiler, Battery Network, GPU Inspector, Benchmark Regression, Source Workspaces, Reverse Net).

#### Scenario: Fresh install shows current layout
- **WHEN** the app launches with no saved layout
- **THEN** the home screen and navigation rail show the current default module order

### Requirement: Unknown persisted IDs are dropped
When restoring a persisted layout, any module ID that is not a recognized current destination ID SHALL be silently dropped, and the remaining recognized IDs SHALL retain their relative order.

#### Scenario: Forward compatibility with removed modules
- **WHEN** a future version removed a module whose ID is present in an older saved layout, and the app loads that layout
- **THEN** the unknown ID is dropped and the remaining modules keep their saved relative order

### Requirement: Column count is stable during a drag
During an in-progress drag, the grid's column count SHALL be frozen to the value computed at drag start, even if the window is resized mid-drag, so cards do not re-flow while being dragged.

#### Scenario: Resize during drag does not reflow
- **WHEN** the user is mid-drag and the window width crosses a column-count breakpoint
- **THEN** the grid keeps the column count it had when the drag started until the drag ends
