## MODIFIED Requirements

### Requirement: Analysis modules support live drag-to-reorder
The home screen's analysis-module grid SHALL let the user reorder module cards by long-pressing a card and dragging it. While the drag is in progress, the other cards SHALL reposition in real time to make room for the dragged card (phone-style), not only on drop.

When a long-press is recognized on a card, the grid SHALL begin a lift animation that visually shrinks then grows the card (to a scale greater than 1.0) and grows its shadow, beginning to follow the pointer in the same gesture without waiting for the lift animation to complete. For the duration of the drag, the dragged card SHALL be rendered as a raised floating card (enlarged scale, elevated shadow, full opacity) that tracks the pointer, drawn above the grid. The dragged card's original grid slot SHALL display a dashed in-place placeholder of the same size; the placeholder SHALL animate with the other cards as they shuffle. On release, the grid SHALL play a settle animation (scale and shadow back to rest) as the floating card returns to the placeholder, which then becomes the real card.

#### Scenario: Live reorder during drag
- **WHEN** the user long-presses a module card and drags it across another card's slot
- **THEN** the displaced cards animate aside immediately as the dragged card passes, before the user releases

#### Scenario: Lift anticipation plays on long-press
- **WHEN** a long-press is recognized on a module card
- **THEN** the card animates a shrink to less than its resting scale followed by a grow to greater than its resting scale, with its shadow increasing, and the card begins following the pointer during this animation with no delay between the press and the follow

#### Scenario: Floating card tracks the pointer
- **WHEN** a drag is in progress
- **THEN** the dragged card is rendered above the grid at an enlarged scale with an elevated shadow and full opacity, positioned at the pointer, and is not rendered inside its original grid slot

#### Scenario: Dashed placeholder marks the home slot
- **WHEN** a drag is in progress
- **THEN** the dragged card's original grid slot shows a dashed-outline placeholder of the same size, and that placeholder moves in real time with the other cards as the grid shuffles

#### Scenario: Settle animation plays on drop
- **WHEN** the user releases the dragged card
- **THEN** the floating card animates its scale and shadow back to rest as it collapses into the placeholder, the placeholder becomes the real card, and the resulting order becomes the active module order

#### Scenario: Drop commits the new order
- **WHEN** the user releases the dragged card over a slot
- **THEN** the card settles into that slot's position and the resulting order becomes the active module order

#### Scenario: Long-press does not open the module
- **WHEN** the user long-presses a card, drags, and releases without having crossed into another slot
- **THEN** the module SHALL NOT open and the order SHALL be unchanged

#### Scenario: Tap still opens the module
- **WHEN** the user taps a module card without a long-press drag gesture
- **THEN** the module opens as before
