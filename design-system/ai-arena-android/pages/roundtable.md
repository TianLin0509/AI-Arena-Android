# Roundtable Page Override

This file overrides `../MASTER.md` for the Android roundtable screen.

## Audience and intent

- Domestic Android users, including middle-aged and older adults.
- Calm, trustworthy utility. Avoid luxury, gaming, glassmorphism and decorative motion.
- The first screen must answer three questions immediately: what can I ask, which AIs are connected, and how do I start.

## Visual tokens

| Role | Value |
|---|---|
| Page background | `#F6F7F9` |
| Surface | `#FFFFFF` |
| Primary | `#1D6078` |
| Primary pressed | `#164A5D` |
| Primary soft | `#E7F2F5` |
| Text primary | `#17232D` |
| Text secondary | `#5B6B77` |
| Border | `#DDE4E8` |
| Success | `#257A50` |
| Success soft | `#E8F5EE` |
| Debate accent | `#71558B` |
| Error | `#B33A3A` |
| Error soft | `#FCEBE9` |

## Typography

- Use the Android system sans-serif stack so Chinese glyphs never fall back inconsistently.
- App title: 22sp / semibold.
- Page title: 26sp / bold / maximum two lines.
- Section title: 17sp / semibold.
- Body: 16sp / line height 24sp.
- Supporting text: 13-14sp, never lighter than `#5B6B77`.
- Avoid extra-bold 34sp hero text and mixed Latin display fonts.

## Layout

- 16dp horizontal page padding; 8dp spacing grid.
- Compact top bar: app name, connected count, and two secondary actions: history and settings.
- Idle home is a non-scrolling single screen containing only members, question, answer mode and primary action.
- Input height is about 168dp; voice input lives inside the field and the character counter is hidden unless invalid.
- Suggestions, recent sessions and accessibility cards do not appear on the idle home.
- History, accessibility, connection management and member selection use dedicated secondary pages with predictable back behavior.
- Answer mode is a two-option segmented control inside one surface, not two oversized cards.
- Primary action uses 56dp height and 16dp radius.
- Participant cards use 14-16dp padding, 12-16dp radius and a subtle 1dp border.
- Latest response appears inside the same participant card to reduce vertical fragmentation.
- Result actions use one primary and one tonal secondary button; destructive stop is text-only red.
- Bottom navigation remains persistent but visually lighter than the main content.

## Interaction and accessibility

- Every control has a minimum 48dp touch target and at least 8dp separation.
- Independent iteration requires a non-blank round Prompt and sends exactly that user text to every participating AI. It must not prepend the original question, previous answers or an app-generated instruction.
- Viewpoint discussion may run without an extra user Prompt; when present, the text is appended as the user's discussion guidance.
- A stale security-challenge iframe must not hide a response that already exists for the active request. Re-extraction reads the response first and reports the challenge only when no matching response exists.
- State is communicated by text plus icon/color; never color alone.
- Visible focus indicator on input and mode selector.
- No animation required for correctness; any transition must stay below 250ms.
- Preserve vertical scrolling and avoid horizontal gestures.
- Do not hide status, errors or retry guidance behind ellipsis.

## Anti-patterns

- No glass blur, gradients, giant empty hero regions or floating decorative shapes.
- No 34sp extra-bold slogan consuming half the first screen.
- No repeated nested cards for one provider and its answer.
- No pale gray text on gray surfaces.
- No exposing internal request IDs or automation metadata.
- No stacked utility cards above the question field on the idle home.
