## ADDED Requirements

### Requirement: The navigation rail has a collapse/expand toggle
The navigation rail SHALL render a toggle control that collapses or expands the rail. In the expanded state the toggle SHALL show an icon and a "Collapse navigation" label; in the collapsed state it SHALL show only a centered icon, with a hover tooltip "Expand navigation".

#### Scenario: Toggle collapses an expanded rail
- **WHEN** the rail is expanded and the user clicks the toggle
- **THEN** the rail collapses to the icon-only width

#### Scenario: Toggle expands a collapsed rail
- **WHEN** the rail is collapsed and the user clicks the toggle
- **THEN** the rail expands to show icons and labels

#### Scenario: Collapsed toggle shows a hover tooltip
- **WHEN** the rail is collapsed and the user hovers the toggle
- **THEN** a tooltip displays "Expand navigation"

### Requirement: The collapse state is purely user-controlled
The rail's collapsed/expanded state SHALL be determined solely by the user's persisted `navigationRailCollapsed` setting. The rail SHALL NOT collapse or expand automatically based on window width. No width-based fallback or breakpoint SHALL influence the state.

#### Scenario: No automatic collapse on a narrow window
- **WHEN** the window is narrower than the former 1280dp breakpoint and the user's saved state is "expanded"
- **THEN** the rail stays expanded (it does not auto-collapse)

#### Scenario: State follows the persisted setting only
- **WHEN** the app launches
- **THEN** the rail's collapsed state equals the persisted `navigationRailCollapsed` value, regardless of window width

### Requirement: The rail width animates on collapse/expand
When the rail collapses or expands, its width SHALL animate smoothly between the collapsed width (54dp) and the expanded width (216dp) rather than snapping.

#### Scenario: Width transitions smoothly
- **WHEN** the user toggles the rail between collapsed and expanded
- **THEN** the rail width animates between 54dp and 216dp

### Requirement: The collapse state persists across launches and stays in sync across entry points
The collapsed state SHALL be persisted to user settings and restored on the next launch. Both the in-rail toggle and the Settings page "Show sidebar titles" toggle SHALL read and write the same `navigationRailCollapsed` field, so a change from either is reflected by the other.

#### Scenario: State persists across restart
- **WHEN** the user toggles the rail and restarts the app
- **THEN** the rail restores the saved collapsed/expanded state

#### Scenario: Settings toggle and rail toggle stay in sync
- **WHEN** the user expands the rail via the in-rail toggle
- **THEN** the Settings page "Show sidebar titles" toggle reflects the expanded state, and vice versa
