import type { Db } from '../index.ts';
import { newId } from '../../ids.ts';
import { normalizeTitle } from '../../library/filenames.ts';
import { isUniqueViolation } from '../index.ts';
import { AppError } from '../../errors.ts';

/**
 * Categories, tags and collections — the three ways a shelf is organised.
 *
 * They are deliberately different shapes rather than one generic "group" table. A category is a
 * single tree the whole household shares and a book sits in exactly one of; tags are a flat
 * many-to-many with no hierarchy; a collection is a user-owned, ordered list that may be private.
 * Collapsing them would mean every entity carries fields that are meaningless for two of the
 * three cases, and the browse filters would have to guess which semantics applied.
 */

export interface CategoryRow {
  id: string;
  parent_id: string | null;
  name: string;
  name_norm: string;
  slug: string;
  path: string;
  position: number;
  description: string | null;
  book_count?: number;
}

export interface TagRow {
  id: string;
  name: string;
  name_norm: string;
  kind: string;
  color: string | null;
  usage_count: number;
}

export interface CollectionRow {
  id: string;
  owner_user_id: string | null;
  name: string;
  name_norm: string;
  kind: string;
  is_public: number;
  position_mode: string;
  description: string | null;
  cover_book_id: string | null;
  created_at: string;
  updated_at: string;
  book_count?: number;
}

function slugOf(name: string): string {
  const ascii = name
    .normalize('NFKD')
    .toLowerCase()
    .replace(/[^a-z0-9]+/gu, '-')
    .replace(/^-+|-+$/gu, '');
  // A pure-CJK name slugs to nothing, so fall back to the normalized form with separators; the
  // slug is only used for URLs and dedupe, never displayed.
  return ascii.length > 0 ? ascii : normalizeTitle(name).replace(/\s+/gu, '-');
}

export class TaxonomyRepo {
  constructor(private readonly db: Db) {}

  // ---------------------------------------------------------------- categories

  listCategories(): CategoryRow[] {
    return this.db.all<CategoryRow>(
      `SELECT c.id, c.parent_id, c.name, c.name_norm, c.slug, c.path, c.position, c.description,
              (SELECT count(*) FROM books b WHERE b.category_id = c.id AND b.deleted_at IS NULL) AS book_count
         FROM categories c
         LEFT JOIN categories p ON p.id = c.parent_id
        -- Ordering by path alone sorted the tree by id, because a path is unique per row — the
        -- position column had no effect on what the API returned, and neither did reordering.
        -- This keeps a root and its sub-shelves together, roots in their own order, children in
        -- theirs.
        ORDER BY ifnull(p.position, c.position), ifnull(p.id, c.id), c.parent_id IS NOT NULL,
                 c.position, c.name`,
    );
  }

  categoryById(id: string): CategoryRow | undefined {
    return this.db.get<CategoryRow>('SELECT * FROM categories WHERE id = ?', [id]);
  }

  /**
   * Creates a category, computing the materialized path from its parent.
   *
   * `path` is stored rather than derived on read so a subtree filter is one prefix scan instead of
   * a recursive CTE on every browse request — and it has to be rewritten for the whole subtree
   * when a category moves, which is what `moveCategory` is for.
   */
  createCategory(input: { name: string; parentId?: string | null; description?: string | null }): CategoryRow {
    const name = input.name.trim();
    if (name.length === 0) throw new AppError('validation_failed', 'Category name must not be empty');
    const nameNorm = normalizeTitle(name);
    const parent = input.parentId ? this.categoryById(input.parentId) : undefined;
    if (input.parentId && !parent) throw new AppError('category_not_found', 'Parent category not found');
    assertCanBeParent(parent);

    const id = newId();
    const parentPath = parent?.path ?? '/';
    const now = new Date().toISOString();
    try {
      this.db.tx(() => {
        this.db.run(
          `INSERT INTO categories (id, parent_id, name, name_norm, slug, path, position, description, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
          [
            id,
            parent?.id ?? null,
            name,
            nameNorm,
            slugOf(name),
            `${parentPath}${id}/`,
            this.nextCategoryPosition(parent?.id ?? null),
            input.description ?? null,
            now,
            now,
          ],
        );
      });
    } catch (err) {
      if (isUniqueViolation(err)) {
        throw new AppError('already_exists', 'A category with that name already exists here', {
          details: { parentId: parent?.id ?? null },
        });
      }
      throw err;
    }
    return this.categoryById(id)!;
  }

  private nextCategoryPosition(parentId: string | null): number {
    return (
      (this.db.get<{ n: number | null }>(
        'SELECT max(position) AS n FROM categories WHERE ifnull(parent_id, \'\') = ifnull(?, \'\')',
        [parentId],
      )?.n ?? -1) + 1
    );
  }

  /**
   * Rewrites the order of one level, root or sub-shelf.
   *
   * The caller sends the whole level rather than "move this one up": positions are dense integers,
   * so patching a single row would either collide with a sibling or leave a gap that the next
   * create would then reuse. Sending the full list makes the intended order explicit, and running
   * the same request twice is a no-op.
   */
  reorderCategories(parentId: string | null, orderedIds: string[]): number {
    const siblings = this.db.all<{ id: string }>(
      "SELECT id FROM categories WHERE ifnull(parent_id, '') = ifnull(?, '')",
      [parentId],
    );
    const known = new Set(siblings.map((row) => row.id));

    if (new Set(orderedIds).size !== orderedIds.length) {
      throw new AppError('validation_failed', 'The order lists the same shelf twice');
    }
    if (orderedIds.length !== known.size || orderedIds.some((id) => !known.has(id))) {
      throw new AppError(
        'validation_failed',
        'The order must list exactly the shelves at that level',
        { details: { expected: known.size, received: orderedIds.length } },
      );
    }

    const now = new Date().toISOString();
    return this.db.tx(() => {
      let changed = 0;
      orderedIds.forEach((id, index) => {
        changed += this.db.run('UPDATE categories SET position = ?, updated_at = ? WHERE id = ?', [
          index,
          now,
          id,
        ]).changes;
      });
      return changed;
    });
  }

  renameCategory(id: string, name: string): CategoryRow {
    const existing = this.categoryById(id);
    if (!existing) throw new AppError('category_not_found', 'Category not found');
    const trimmed = name.trim();
    if (trimmed.length === 0) throw new AppError('validation_failed', 'Category name must not be empty');
    try {
      this.db.run('UPDATE categories SET name = ?, name_norm = ?, updated_at = ? WHERE id = ?', [
        trimmed,
        normalizeTitle(trimmed),
        new Date().toISOString(),
        id,
      ]);
    } catch (err) {
      if (isUniqueViolation(err)) {
        throw new AppError('already_exists', 'A category with that name already exists here');
      }
      throw err;
    }
    return this.categoryById(id)!;
  }

  /**
   * Re-parents a category and rewrites the path of its whole subtree.
   *
   * Refuses to move a category into its own descendant, which would detach the subtree from the
   * tree entirely: the path update would then rewrite a prefix that no longer roots anywhere, and
   * those categories would simply stop appearing in the navigation.
   */
  moveCategory(id: string, newParentId: string | null): CategoryRow {
    const node = this.categoryById(id);
    if (!node) throw new AppError('category_not_found', 'Category not found');
    const parent = newParentId ? this.categoryById(newParentId) : null;
    if (newParentId && !parent) throw new AppError('category_not_found', 'Target category not found');
    if (newParentId === id) throw new AppError('unprocessable', 'A category cannot be its own parent');
    if (parent && parent.path.startsWith(node.path)) {
      throw new AppError('unprocessable', 'A category cannot be moved inside its own subtree');
    }
    assertCanBeParent(parent ?? undefined);
    if (parent) assertHasNoChildren(this.db, node.id);

    const newPath = `${parent?.path ?? '/'}${node.id}/`;
    const oldPath = node.path;
    this.db.tx(() => {
      this.db.run('UPDATE categories SET parent_id = ?, path = ?, updated_at = ? WHERE id = ?', [
        parent?.id ?? null,
        newPath,
        new Date().toISOString(),
        id,
      ]);
      // Rewrite descendants by string surgery on the stored prefix: one UPDATE instead of a walk,
      // and safe because the path is derived from ids that never change.
      this.db.run(
        `UPDATE categories SET path = ? || substr(path, ?), updated_at = ?
          WHERE path LIKE ? ESCAPE '\\' AND id <> ?`,
        [newPath, oldPath.length + 1, new Date().toISOString(), `${oldPath}%`, id],
      );
    });
    return this.categoryById(id)!;
  }

  /**
   * Deletes a category. `mode` decides what happens to the books that were in it, because leaving
   * them uncategorised and deleting the books are very different outcomes and neither is obviously
   * right — so the caller says which.
   */
  deleteCategory(id: string, mode: 'orphan' | 'cascade-to-parent'): void {
    const node = this.categoryById(id);
    if (!node) throw new AppError('category_not_found', 'Category not found');
    this.db.tx(() => {
      if (mode === 'cascade-to-parent' && node.parent_id) {
        this.db.run('UPDATE books SET category_id = ?, updated_at = ? WHERE category_id = ?', [
          node.parent_id,
          new Date().toISOString(),
          id,
        ]);
      } else {
        this.db.run('UPDATE books SET category_id = NULL, updated_at = ? WHERE category_id = ?', [
          new Date().toISOString(),
          id,
        ]);
      }
      // Children move up rather than being deleted with the parent: a category is navigation, and
      // silently removing a subtree of books' labels is not what "delete this folder" should mean.
      this.db.run('UPDATE categories SET parent_id = ?, updated_at = ? WHERE parent_id = ?', [
        node.parent_id,
        new Date().toISOString(),
        id,
      ]);
      this.db.run('DELETE FROM categories WHERE id = ?', [id]);
    });
  }

  // ---------------------------------------------------------------- tags

  listTags(query?: string): TagRow[] {
    if (query && query.trim().length > 0) {
      return this.db.all<TagRow>(
        `SELECT id, name, name_norm, kind, color, usage_count FROM tags
          WHERE name_norm LIKE ? ESCAPE '\\' ORDER BY usage_count DESC, name LIMIT 200`,
        [`%${normalizeTitle(query).replace(/[\\%_]/gu, (c) => `\\${c}`)}%`],
      );
    }
    return this.db.all<TagRow>(
      'SELECT id, name, name_norm, kind, color, usage_count FROM tags ORDER BY usage_count DESC, name LIMIT 500',
    );
  }

  ensureTag(name: string, kind = 'tag'): TagRow {
    const trimmed = name.trim();
    if (trimmed.length === 0) throw new AppError('validation_failed', 'Tag name must not be empty');
    const norm = normalizeTitle(trimmed);
    const existing = this.db.get<TagRow>('SELECT * FROM tags WHERE name_norm = ?', [norm]);
    if (existing) return existing;
    const id = newId();
    this.db.run('INSERT INTO tags (id, name, name_norm, kind, created_at) VALUES (?, ?, ?, ?, ?)', [
      id,
      trimmed,
      norm,
      kind,
      new Date().toISOString(),
    ]);
    return this.db.get<TagRow>('SELECT * FROM tags WHERE id = ?', [id])!;
  }

  /**
   * Merges tags into one. Retagging happens in a transaction with INSERT OR IGNORE so a book that
   * carried both source and target keeps a single membership rather than tripping the primary key
   * halfway through and leaving the merge half-applied.
   */
  mergeTags(sourceIds: string[], targetId: string): { merged: number; books: number } {
    const target = this.db.get<TagRow>('SELECT * FROM tags WHERE id = ?', [targetId]);
    if (!target) throw new AppError('not_found', 'Target tag not found');
    const sources = sourceIds.filter((id) => id !== targetId);
    if (sources.length === 0) return { merged: 0, books: 0 };

    let books = 0;
    this.db.tx(() => {
      for (const sourceId of sources) {
        const moved = this.db.run(
          `INSERT OR IGNORE INTO book_tags (book_id, tag_id, added_by, added_at)
           SELECT book_id, ?, added_by, added_at FROM book_tags WHERE tag_id = ?`,
          [targetId, sourceId],
        );
        books += moved.changes;
        this.db.run('DELETE FROM book_tags WHERE tag_id = ?', [sourceId]);
        this.db.run('DELETE FROM tags WHERE id = ?', [sourceId]);
      }
      this.refreshTagCounters();
    });
    return { merged: sources.length, books };
  }

  deleteTag(id: string): void {
    this.db.tx(() => {
      this.db.run('DELETE FROM book_tags WHERE tag_id = ?', [id]);
      this.db.run('DELETE FROM tags WHERE id = ?', [id]);
      this.refreshTagCounters();
    });
  }

  refreshTagCounters(): void {
    this.db.run('UPDATE tags SET usage_count = (SELECT count(*) FROM book_tags bt WHERE bt.tag_id = tags.id)');
  }

  // ---------------------------------------------------------------- collections

  /**
   * A user sees their own collections plus every shared one. Ownership is the whole rule; there is
   * no sharing matrix, which is the decision recorded in the design: a household library does not
   * need per-collection ACLs, and pretending otherwise would be a permission system nobody audits.
   */
  listCollections(userId: string): CollectionRow[] {
    return this.db.all<CollectionRow>(
      `SELECT c.id, c.owner_user_id, c.name, c.name_norm, c.kind, c.is_public, c.position_mode,
              c.description, c.cover_book_id, c.created_at, c.updated_at,
              (SELECT count(*) FROM collection_books cb WHERE cb.collection_id = c.id) AS book_count
         FROM collections c
        WHERE c.deleted_at IS NULL AND (c.owner_user_id IS NULL OR c.owner_user_id = ?)
        ORDER BY c.owner_user_id IS NULL DESC, c.name`,
      [userId],
    );
  }

  collectionById(id: string): CollectionRow | undefined {
    return this.db.get<CollectionRow>('SELECT * FROM collections WHERE id = ? AND deleted_at IS NULL', [id]);
  }

  requireEditableCollection(id: string, userId: string, canManageGlobal: boolean): CollectionRow {
    const row = this.collectionById(id);
    if (!row) throw new AppError('collection_not_found', 'Collection not found');
    if (row.owner_user_id === null && !canManageGlobal) {
      throw new AppError('forbidden', 'This collection is shared; editing it requires collections:global');
    }
    if (row.owner_user_id !== null && row.owner_user_id !== userId && !canManageGlobal) {
      throw new AppError('forbidden', 'This collection belongs to someone else');
    }
    return row;
  }

  createCollection(input: {
    name: string;
    ownerUserId: string | null;
    description?: string | null;
    positionMode?: 'manual' | 'added' | 'title';
  }): CollectionRow {
    const name = input.name.trim();
    if (name.length === 0) throw new AppError('validation_failed', 'Collection name must not be empty');
    const id = newId();
    const now = new Date().toISOString();
    try {
      this.db.run(
        `INSERT INTO collections (id, owner_user_id, name, name_norm, kind, position_mode, description, created_at, updated_at)
         VALUES (?, ?, ?, ?, 'collection', ?, ?, ?, ?)`,
        [
          id,
          input.ownerUserId,
          name,
          normalizeTitle(name),
          input.positionMode ?? 'manual',
          input.description ?? null,
          now,
          now,
        ],
      );
    } catch (err) {
      if (isUniqueViolation(err)) {
        throw new AppError('already_exists', 'You already have a collection with that name');
      }
      throw err;
    }
    return this.collectionById(id)!;
  }

  updateCollection(
    id: string,
    patch: { name?: string; description?: string | null; positionMode?: 'manual' | 'added' | 'title' },
  ): CollectionRow {
    const sets: string[] = [];
    const values: (string | number | null)[] = [];
    if (patch.name !== undefined) {
      sets.push('name = ?', 'name_norm = ?');
      values.push(patch.name.trim(), normalizeTitle(patch.name));
    }
    if (patch.description !== undefined) {
      sets.push('description = ?');
      values.push(patch.description);
    }
    if (patch.positionMode !== undefined) {
      sets.push('position_mode = ?');
      values.push(patch.positionMode);
    }
    if (sets.length > 0) {
      this.db.run(`UPDATE collections SET ${sets.join(', ')}, updated_at = ? WHERE id = ?`, [
        ...values,
        new Date().toISOString(),
        id,
      ]);
    }
    return this.collectionById(id)!;
  }

  /** Soft delete, so another device learns the collection is gone instead of it reappearing. */
  deleteCollection(id: string): void {
    const now = new Date().toISOString();
    this.db.tx(() => {
      this.db.run('UPDATE collections SET deleted_at = ?, updated_at = ? WHERE id = ?', [now, now, id]);
      this.db.run('DELETE FROM collection_books WHERE collection_id = ?', [id]);
    });
  }

  /**
   * Adds books, preserving the order they arrived in.
   *
   * Positions are appended after the current maximum rather than computed from the array index:
   * adding to a hand-ordered shelf must not silently renumber the books already on it.
   */
  addBooks(collectionId: string, bookIds: string[], userId: string | null): { added: number; skipped: number } {
    const now = new Date().toISOString();
    return this.db.tx(() => {
      const start =
        (this.db.get<{ n: number | null }>(
          'SELECT max(position) AS n FROM collection_books WHERE collection_id = ?',
          [collectionId],
        )?.n ?? -1) + 1;
      let added = 0;
      let position = start;
      for (const bookId of bookIds) {
        const exists = this.db.get('SELECT 1 FROM books WHERE id = ? AND deleted_at IS NULL', [bookId]);
        if (!exists) continue;
        const result = this.db.run(
          `INSERT OR IGNORE INTO collection_books (collection_id, book_id, position, added_by, added_at)
           VALUES (?, ?, ?, ?, ?)`,
          [collectionId, bookId, position, userId, now],
        );
        if (result.changes > 0) {
          added += 1;
          position += 1;
        }
      }
      return { added, skipped: bookIds.length - added };
    });
  }

  removeBooks(collectionId: string, bookIds: string[]): number {
    return this.db.tx(() => {
      let removed = 0;
      for (const bookId of bookIds) {
        removed += this.db.run('DELETE FROM collection_books WHERE collection_id = ? AND book_id = ?', [
          collectionId,
          bookId,
        ]).changes;
      }
      return removed;
    });
  }

  reorderCollection(collectionId: string, orderedBookIds: string[]): number {
    return this.db.tx(() => {
      let changed = 0;
      orderedBookIds.forEach((bookId, index) => {
        changed += this.db.run(
          'UPDATE collection_books SET position = ? WHERE collection_id = ? AND book_id = ?',
          [index, collectionId, bookId],
        ).changes;
      });
      return changed;
    });
  }
}

/**
 * Shelves nest exactly one level, because that is all the app draws: a shelf, and the sub-shelves
 * inside it. The server used to accept any depth, which meant a third level was invisible on every
 * client — data that exists but cannot be shown is worse than a rejected write.
 *
 * `0009_category_two_levels` flattens anything deeper and adds the same rule as a trigger, so this
 * check produces the readable error while the database keeps the invariant even if a future code
 * path forgets to call it.
 */
function assertCanBeParent(parent: CategoryRow | undefined): void {
  if (!parent) return;
  if (parent.parent_id !== null) {
    throw new AppError(
      'validation_failed',
      'A sub-shelf cannot contain another shelf: shelves nest one level only',
      { details: { parentId: parent.id } },
    );
  }
}

/** Moving a shelf that owns sub-shelves under another shelf would create a third level. */
function assertHasNoChildren(db: Db, id: string): void {
  const child = db.get<{ id: string }>('SELECT id FROM categories WHERE parent_id = ? LIMIT 1', [id]);
  if (child) {
    throw new AppError(
      'validation_failed',
      'A shelf that has sub-shelves cannot itself be moved into another shelf',
      { details: { id, childId: child.id } },
    );
  }
}
