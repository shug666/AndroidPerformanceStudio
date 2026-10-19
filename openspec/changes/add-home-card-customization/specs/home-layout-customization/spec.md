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

### Requirement: Quick actions support drag-to-reorder
The quick-action row SHALL let the user reorder action cards by long-press-drag, with live repositioning of the other action cards during the drag, mirroring the module grid behavior.

#### Scenario: Reorder quick actions by drag
- **WHEN** the user long-press-drags a quick-action card past another
- **THEN** the other quick-action cards shift aside in real time and the new order commits on release

### Requirement: Quick actions are customizable from a built-in catalog
The system SHALL expose a finite built-in catalog of quick actions. The catalog SHALL contain at minimum the four current quick actions (start capture, import trace, analyze HPROF, run startup test). The user SHALL be able to add actions from the catalog to their visible quick-action set and remove actions from their visible set. The system SHALL NOT support user-defined arbitrary commands, labels, icons, or actions.

#### Scenario: Add an action from the catalog
- **WHEN** the user opens the quick-action management dialog, picks a catalog action not currently shown, and confirms
- **THEN** that action appears in the quick-action row

#### Scenario: Remove a visible action
- **WHEN** the user opens the management dialog and removes a currently shown action
- **THEN** that action disappears from the quick-action row and is available to re-add later from the catalog

#### Scenario: Catalog is finite
- **WHEN** the user views the management dialog
- **THEN** the available actions are limited to the built-in catalog; there is no option to create a custom label/icon/command action

### Requirement: Quick-action row has an Edit entry
The quick-action row SHALL end with an always-present "＋ Edit" entry after the last action card. The Edit entry SHALL open the quick-action management dialog. The Edit entry SHALL NOT be draggable or reorderable.

#### Scenario: Edit entry opens management
- **WHEN** the user clicks the "＋ Edit" entry
- **THEN** the management dialog for adding/removing quick actions opens

#### Scenario: Edit entry stays at the end
- **WHEN** the user reorders quick actions
- **THEN** the Edit entry remains in the trailing slot and cannot be moved or removed

### Requirement: Layout is persisted across launches
The active module order and the visible quick-action set (with its order) SHALL be persisted to user settings and restored on the next launch.

#### Scenario: Module order persists
- **WHEN** the user reorders modules and restarts the app
- **THEN** the module grid and navigation rail restore the user's saved module order

#### Scenario: Quick-action set persists
- **WHEN** the user adds, removes, or reorders quick actions and restarts the app
- **THEN** the quick-action row restores the saved set and order

### Requirement: Navigation rail follows the module order
The navigation rail's module entries SHALL be rendered in the persisted module order, with Home pinned as the first entry. Reordering modules on the home screen SHALL reorder the rail.

#### Scenario: Rail reflects home reorder
- **WHEN** the user reorders modules on the home screen
- **THEN** the navigation rail entries (excluding the pinned Home entry) appear in the new order

#### Scenario: Home stays pinned in the rail
- **WHEN** the module order is changed in any way
- **THEN** Home remains the first navigation-rail entry and is never reordered by module customization

### Requirement: Defaults preserve current behavior
On first launch with no persisted layout, the module order SHALL equal the current hardcoded order (Layout Inspector, Simpleperf, Perfetto, Memory Profiler, Frame Profiler, Startup Profiler, Battery Network, GPU Inspector, Benchmark Regression, Source Workspaces, Reverse Net) and the visible quick actions SHALL be the current four (Start Capture, Import Trace, Analyze HPROF, Run Startup Test).

#### Scenario: Fresh install shows current layout
- **WHEN** the app launches with no saved layout
- **THEN** the home screen and navigation rail show the current default module order and the four default quick actions

### Requirement: Unknown persisted IDs are dropped
When restoring a persisted layout, any module or quick-action ID that is not a recognized current catalog/destination ID SHALL be silently dropped, and the remaining recognized IDs SHALL retain their relative order.

#### Scenario: Forward compatibility with removed modules
- **WHEN** a future version removed a module whose ID is present in an older saved layout, and the app loads that layout
- **THEN** the unknown ID is dropped and the remaining modules keep their saved relative order

### Requirement: Column count is stable during a drag
During an in-progress drag, the grid's column count SHALL be frozen to the value computed at drag start, even if the window is resized mid-drag, so cards do not re-flow while being dragged.

#### Scenario: Resize during drag does not reflow
- **WHEN** the user is mid-drag and the window width crosses a column-count breakpoint
- **THEN** the grid keeps the column count it had when the drag started until the drag ends
