---
name: Azure Clarity
colors:
  surface: '#f7f9fb'
  surface-dim: '#d8dadc'
  surface-bright: '#f7f9fb'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f2f4f6'
  surface-container: '#eceef0'
  surface-container-high: '#e6e8ea'
  surface-container-highest: '#e0e3e5'
  on-surface: '#191c1e'
  on-surface-variant: '#424754'
  inverse-surface: '#2d3133'
  inverse-on-surface: '#eff1f3'
  outline: '#727785'
  outline-variant: '#c2c6d6'
  surface-tint: '#005ac2'
  primary: '#0058be'
  on-primary: '#ffffff'
  primary-container: '#2170e4'
  on-primary-container: '#fefcff'
  inverse-primary: '#adc6ff'
  secondary: '#505f76'
  on-secondary: '#ffffff'
  secondary-container: '#d0e1fb'
  on-secondary-container: '#54647a'
  tertiary: '#00628d'
  on-tertiary: '#ffffff'
  tertiary-container: '#007cb1'
  on-tertiary-container: '#fcfcff'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#d8e2ff'
  primary-fixed-dim: '#adc6ff'
  on-primary-fixed: '#001a42'
  on-primary-fixed-variant: '#004395'
  secondary-fixed: '#d3e4fe'
  secondary-fixed-dim: '#b7c8e1'
  on-secondary-fixed: '#0b1c30'
  on-secondary-fixed-variant: '#38485d'
  tertiary-fixed: '#c9e6ff'
  tertiary-fixed-dim: '#89ceff'
  on-tertiary-fixed: '#001e2f'
  on-tertiary-fixed-variant: '#004c6e'
  background: '#f7f9fb'
  on-background: '#191c1e'
  surface-variant: '#e0e3e5'
typography:
  display-lg:
    fontFamily: Hanken Grotesk
    fontSize: 48px
    fontWeight: '700'
    lineHeight: 56px
    letterSpacing: -0.02em
  headline-lg:
    fontFamily: Hanken Grotesk
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.01em
  headline-lg-mobile:
    fontFamily: Hanken Grotesk
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
  body-md:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-sm:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  label-caps:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.05em
rounded:
  sm: 0.25rem
  DEFAULT: 0.5rem
  md: 0.75rem
  lg: 1rem
  xl: 1.5rem
  full: 9999px
spacing:
  base: 4px
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
  2xl: 48px
  gutter: 24px
  margin-mobile: 16px
  margin-desktop: 40px
---

## Brand & Style
The design system is built upon a foundation of **Modern Minimalism** with subtle **Glassmorphic** accents. It targets high-productivity environments where clarity, speed, and cognitive ease are paramount. The aesthetic is intentionally airy and precise, utilizing generous whitespace to allow the primary blue to act as a functional beacon rather than just decoration. 

The emotional response should be one of "effortless control"—professional and reliable, yet fresh and unburdened by legacy enterprise clutter. High-quality typography and a restrained use of depth ensure the UI feels sophisticated and contemporary.

## Colors
The palette is centered around a vibrant Primary Blue (#3b82f6), which serves as the main action color. This is supported by a Secondary Slate (#64748b) for utility and balanced metadata. 

To maintain the light mode aesthetic:
- **Primary Container:** Use a very pale blue tint (#eff6ff) for large background areas or grouped elements to provide a subtle distinctness from the pure white surface.
- **Secondary Container:** A soft neutral gray (#f1f5f9) is used for secondary content blocks to prevent color fatigue.
- **Surface:** The base layer is pure white (#ffffff) to maximize contrast and perceived cleanliness.
- **Text:** Use Slate 900 for high-contrast headlines and Slate 600 for body text to maintain a soft, professional readability.

## Typography
This design system employs a tiered font strategy to balance personality with utility. **Hanken Grotesk** is used for headlines to provide a sharp, contemporary edge. **Inter** handles the heavy lifting of body copy due to its exceptional legibility and systematic feel. **JetBrains Mono** is introduced for labels and small metadata to inject a subtle "technical" precision into the interface.

On mobile devices, large headlines automatically scale down (e.g., 32px to 24px) to ensure layout integrity and readability. Use tight tracking on large display text and slightly increased tracking for monospaced labels.

## Layout & Spacing
The system utilizes a **12-column fluid grid** for desktop and a **4-column grid** for mobile. The spacing rhythm is based on a 4px baseline, ensuring all components align to a predictable mathematical scale.

- **Desktop:** 40px outer margins with 24px gutters.
- **Tablet:** 32px outer margins with 16px gutters.
- **Mobile:** 16px outer margins with 12px gutters.

Spacing should be applied asymmetrically to create hierarchy; use `xl` or `2xl` spacing to separate major sections, while using `sm` or `md` for internal component grouping.

## Elevation & Depth
Depth is conveyed through **Tonal Layering** and **Ambient Shadows**. Instead of heavy black shadows, this design system uses soft, diffused shadows tinted with the primary or neutral color to maintain a "light" feel.

- **Level 0 (Base):** White or Primary Container backgrounds.
- **Level 1 (Cards):** Low-contrast outlines (1px solid #e2e8f0) or a very soft shadow (0 4px 12px rgba(59, 130, 246, 0.05)).
- **Level 2 (Overlays/Dropdowns):** Medium-diffusion shadows with a subtle backdrop blur (8px) to create a frosted-glass effect on top of content.
- **Level 3 (Modals):** High-diffusion shadows (0 20px 40px rgba(0, 0, 0, 0.1)) to pull the element significantly forward.

## Shapes
The shape language is consistently **Rounded**, striking a balance between friendly and professional. 

- **Standard Elements (Buttons, Inputs):** 0.5rem (8px) radius.
- **Large Elements (Cards, Containers):** 1rem (16px) radius.
- **Extra Large Elements (Modals, Hero sections):** 1.5rem (24px) radius.
- **Specialty Elements (Chips, Status Tags):** Full pill-shape for maximum distinction from interactive buttons.

## Components
- **Buttons:** Primary buttons use a solid #3b82f6 fill with white text. Secondary buttons use the Primary Container fill (#eff6ff) with #3b82f6 text for a softer interactive feel.
- **Input Fields:** 1px border (#cbd5e1) that transitions to 2px Primary Blue (#3b82f6) on focus, accompanied by a soft blue outer glow.
- **Chips/Tags:** Use the Primary Container color for background and Primary color for text. For status-specific chips (e.g., Success, Warning), use a desaturated version of the respective semantic color.
- **Lists:** Clean rows separated by 1px neutral borders (#f1f5f9). Interactive list items should have a Primary Container hover state.
- **Cards:** White background with a subtle Level 1 shadow and 16px rounded corners. Use 24px internal padding for content-heavy cards.
- **Checkboxes/Radios:** Circular or rounded-square indicators using Primary Blue for the active state, ensuring the "check" mark is always white.