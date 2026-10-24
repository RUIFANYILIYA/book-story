import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { parseWith, idSchema, idListSchema } from '../../http/validate.ts';
import { AppError } from '../../errors.ts';
import { createGuards } from '../../auth/guard.ts';
import { TaxonomyRepo } from '../../db/repositories/taxonomy.repo.ts';
import type { Db } from '../../db/index.ts';
import type { SessionService } from '../../auth/sessions.ts';

const NameSchema = z.string().trim().min(1).max(200);

export function registerTaxonomyRoutes(app: FastifyInstance, deps: { db: Db; session: SessionService }): void {
  const taxonomy = new TaxonomyRepo(deps.db);
  const guards = createGuards(deps.session);
  const authed = { preHandler: [guards.authenticate] };
  const read = { preHandler: [guards.authenticate, guards.require('library:read')] };
  const edit = { preHandler: [guards.authenticate, guards.require('library:edit')] };

  // ------------------------------------------------------------------ categories

  app.get('/api/v1/categories', read, async () => ({ items: taxonomy.listCategories() }));

  app.post('/api/v1/categories', edit, async (request, reply) => {
    const body = parseWith(
      z.object({
        name: NameSchema,
        parentId: idSchema.nullish(),
        description: z.string().max(2000).nullish(),
      }),
      request.body,
      'body',
    );
    const created = taxonomy.createCategory({
      name: body.name,
      parentId: body.parentId ?? null,
      description: body.description ?? null,
    });
    return reply.status(201).send({ category: created });
  });

  app.patch('/api/v1/categories/:id', edit, async (request) => {
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const body = parseWith(
      z.object({ name: NameSchema.optional(), parentId: idSchema.nullish() }),
      request.body,
      'body',
    );
    if (body.name !== undefined) taxonomy.renameCategory(id, body.name);
    if (body.parentId !== undefined) taxonomy.moveCategory(id, body.parentId ?? null);
    return { category: taxonomy.categoryById(id) };
  });

  /**
   * Reordering takes the whole level, not "move this one up": positions are dense integers, so a
   * single-row patch either collides with a sibling or leaves a gap. `parentId: null` reorders the
   * root shelves, which is the order the app's tabs use.
   */
  app.post('/api/v1/categories/reorder', edit, async (request) => {
    const body = parseWith(
      z.object({
        parentId: idSchema.nullish(),
        ids: z.array(idSchema).min(1).max(500),
      }),
      request.body,
      'body',
    );
    return { reordered: taxonomy.reorderCategories(body.parentId ?? null, body.ids) };
  });

  app.delete('/api/v1/categories/:id', edit, async (request, reply) => {    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const query = parseWith(
      z.object({ mode: z.enum(['orphan', 'cascade-to-parent']).default('orphan') }),
      request.query,
      'query',
    );
    taxonomy.deleteCategory(id, query.mode);
    return reply.status(204).send();
  });

  // ------------------------------------------------------------------ tags

  app.get('/api/v1/tags', read, async (request) => {
    const query = parseWith(z.object({ q: z.string().trim().max(200).optional() }), request.query, 'query');
    return { items: taxonomy.listTags(query.q) };
  });

  app.post('/api/v1/tags', edit, async (request, reply) => {
    const body = parseWith(
      z.object({ name: NameSchema, kind: z.enum(['tag', 'genre', 'subject']).default('tag') }),
      request.body,
      'body',
    );
    return reply.status(201).send({ tag: taxonomy.ensureTag(body.name, body.kind) });
  });

  app.post('/api/v1/tags/merge', edit, async (request) => {
    const body = parseWith(
      z.object({ sourceIds: z.array(idSchema).min(1).max(200), targetId: idSchema }),
      request.body,
      'body',
    );
    return taxonomy.mergeTags(body.sourceIds, body.targetId);
  });

  app.delete('/api/v1/tags/:id', edit, async (request, reply) => {
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    taxonomy.deleteTag(id);
    return reply.status(204).send();
  });

  // ------------------------------------------------------------------ collections

  app.get('/api/v1/collections', read, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    return { items: taxonomy.listCollections(auth.user.id) };
  });

  app.post('/api/v1/collections', authed, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const body = parseWith(
      z.object({
        name: NameSchema,
        description: z.string().max(2000).nullish(),
        positionMode: z.enum(['manual', 'added', 'title']).default('manual'),
        shared: z.boolean().default(false),
      }),
      request.body,
      'body',
    );
    // A shared collection is visible to everyone, so creating one is a library-level write.
    if (body.shared && !auth.capabilities.includes('collections:global')) {
      throw new AppError('capability_denied', 'Shared collections require collections:global', {
        details: { capability: 'collections:global' },
      });
    }
    const created = taxonomy.createCollection({
      name: body.name,
      ownerUserId: body.shared ? null : auth.user.id,
      description: body.description ?? null,
      positionMode: body.positionMode,
    });
    return reply.status(201).send({ collection: created });
  });

  app.patch('/api/v1/collections/:id', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    taxonomy.requireEditableCollection(id, auth.user.id, auth.capabilities.includes('collections:global'));
    const body = parseWith(
      z.object({
        name: NameSchema.optional(),
        description: z.string().max(2000).nullish(),
        positionMode: z.enum(['manual', 'added', 'title']).optional(),
      }),
      request.body,
      'body',
    );
    return { collection: taxonomy.updateCollection(id, body) };
  });

  app.delete('/api/v1/collections/:id', authed, async (request, reply) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    taxonomy.requireEditableCollection(id, auth.user.id, auth.capabilities.includes('collections:global'));
    taxonomy.deleteCollection(id);
    return reply.status(204).send();
  });

  app.get('/api/v1/collections/:id/books', read, async (request) => {
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    const collection = taxonomy.collectionById(id);
    if (!collection) throw new AppError('collection_not_found', 'Collection not found');
    // Returned in the collection's own order, which is the only reason a collection is not a tag.
    return {
      items: deps.db.all(
        `SELECT b.id, b.title, b.sort_key, b.has_cover, b.content_updated_at, cb.position, cb.note
           FROM collection_books cb JOIN books b ON b.id = cb.book_id
          WHERE cb.collection_id = ? AND b.deleted_at IS NULL
          ORDER BY cb.position, b.id`,
        [id],
      ),
    };
  });

  app.put('/api/v1/collections/:id/books', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    taxonomy.requireEditableCollection(id, auth.user.id, auth.capabilities.includes('collections:global'));
    const body = parseWith(z.object({ bookIds: idListSchema }), request.body, 'body');
    return taxonomy.addBooks(id, body.bookIds, auth.user.id);
  });

  app.delete('/api/v1/collections/:id/books', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    taxonomy.requireEditableCollection(id, auth.user.id, auth.capabilities.includes('collections:global'));
    const body = parseWith(z.object({ bookIds: idListSchema }), request.body, 'body');
    return { removed: taxonomy.removeBooks(id, body.bookIds) };
  });

  app.patch('/api/v1/collections/:id/order', authed, async (request) => {
    const auth = request.auth;
    if (!auth) throw new AppError('unauthorized', 'Authentication required');
    const { id } = parseWith(z.object({ id: idSchema }), request.params, 'params');
    taxonomy.requireEditableCollection(id, auth.user.id, auth.capabilities.includes('collections:global'));
    const body = parseWith(z.object({ bookIds: idListSchema }), request.body, 'body');
    return { reordered: taxonomy.reorderCollection(id, body.bookIds) };
  });
}
