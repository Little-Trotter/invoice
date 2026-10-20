Shared UI components for the Kotlin invoice UI.

Components:
- HeroCard: standardized hero header for dialogs and pages (sp-dialog-hero, sp-hero-avatar, sp-hero-name, sp-hero-sub)
- Section: section wrapper with colored bar and title (sp-section, sp-section-bar, sp-section-title)
- ActionBar: standardized action bar for dialog footers (sp-action-bar)
- GridWrapper: simple wrapper to provide consistent spacing around Grid instances (sp-grid-wrapper)
- Pager: small pager component with previous/next and page indicator; integrates with views by calling onPageChange
- DataTable: Grid subclass providing helper columns (actions, contact, money) to standardize common grid patterns
- ActionStatusSpan (actionStatusSpan()): live-region status message span (role=status, aria-live=polite) for panel action feedback

Usage:
- Prefer HeroCard / Section / ActionBar when building dialogs or complex forms.
- Wrap all Grid instances with GridWrapper(grid) to maintain consistent spacing and allow adding a Pager later.
- For large lists where explicit pagination is required, use Pager and GridWrapper.withPager(pager).

Styling is centralized in src/main/frontend/themes/littletrotter/styles.css under sp-* classes.

Best practices:
- Add explicit lambda parameter types in Grid.addColumn/addComponentColumn to avoid Kotlin overload issues.
- Keep visual tokens (spacing, colors, avatar sizes) in the littletrotter theme and prefer CSS variables when adding rules.
- Run `mvn -f kotlin-invoice/pom.xml -DskipTests package` after making UI edits to verify compilation quickly before running full tests.

