package com.krizaka.orazaka.studioservice.domain.port;

import com.krizaka.orazaka.studio.domain.model.PackBundle;

/**
 * Outbound port: write everything a bundle contributes to the Studio catalogue, or nothing.
 *
 * <p>The write twin of {@link PackRepository}, and separate from it for the reason the read port
 * gives for existing at all: reads resolve an i18n overlay and a bundle join and hand back a {@code
 * Pack}; a write applies seven tables in one statement order. Folding both into one interface would
 * give the catalogue service a save method it must never call.
 *
 * <p><b>One write method, on purpose.</b> The unit of installation is the bundle, not the row. A
 * port offering {@code saveStudio}, {@code saveBlueprint} and {@code saveTranslation} would let a
 * caller apply three of them and stop, which is exactly the half-installed catalogue the
 * transaction exists to make impossible. {@link #isApplied} asks about the same unit for the same
 * reason.
 */
public interface PackInstallRepository {

  /**
   * Applies a bundle's catalogue slice in a single transaction.
   *
   * <p>Idempotent by key: re-applying a bundle updates the rows it owns rather than failing on a
   * duplicate. That is what lets a failed install be retried instead of unpicked, and it is why
   * every statement is an upsert rather than an insert.
   *
   * <p>Blueprints are the exception the database enforces: a PUBLISHED version is immutable ({@code
   * trg_studio_blueprint_immutable}), so re-installing a bundle at the same version leaves the
   * published blueprint exactly as it was rather than rewriting it. Shipping a changed blueprint
   * means shipping a new version, which is ADR-034 §5 and not something an installer may route
   * around.
   *
   * @param bundle the resolved manifest
   * @return how many Studios the bundle wrote
   */
  int apply(PackBundle bundle);

  /**
   * Whether this catalogue already carries the bundle, at the version it ships.
   *
   * <p>The question bootstrap asks (ADR-068, {@code #45}), and the reason it is asked at all:
   * {@link #apply} is an upsert, so a bootstrap that simply applied every shipped bundle at every
   * start would work — and would silently overwrite an admin's catalogue edit on the next restart.
   * The catalogue is data an admin may change without a deploy (ADR-034); a bootstrap exists to
   * make a <i>fresh</i> environment whole, not to hold every environment at the shipped manifest.
   *
   * <p>Judged on the Studios, not on the pack row: two shipped bundles are catalogued under no pack
   * at all, so a {@code pack} lookup would report them missing forever. A bundle counts as carried
   * when every Studio it ships exists at the blueprint version it ships — which makes a version
   * bump in a shipped manifest install itself, and leaves everything else alone.
   *
   * @param bundle the resolved manifest
   * @return {@code true} when nothing in this bundle is missing from the catalogue
   */
  boolean isApplied(PackBundle bundle);

  /**
   * Removes a bundle's catalogue slice.
   *
   * <p>For compensation, and for uninstalling. Cascades follow the schema's own {@code ON DELETE
   * CASCADE} from {@code pack} and {@code studio}; an installation an actor already holds is
   * deliberately NOT removed, because deleting someone's workspace to undo an admin's failed
   * install would be a worse outcome than the failure.
   *
   * @param bundleKey the bundle to remove
   * @return {@code true} when anything was removed
   */
  boolean remove(String bundleKey);
}
