# Agent Instructions

## OpenSpec workflow

This repo uses OpenSpec for spec-driven development.

Before writing implementation code for any non-trivial change:

1. Read `openspec/project.md`.
2. Check existing specs under `openspec/specs/`.
3. If the request changes behavior, create or update an OpenSpec change under:
   `openspec/changes/<change-id>/`
4. The change must include:
   - `proposal.md`
   - `tasks.md`
   - spec deltas under `specs/<capability>/spec.md`
   - `design.md` when architecture, security, data model, external integration, or deployment is affected
5. Do not modify application code during proposal creation.
6. Run OpenSpec validation before implementation:
   `openspec validate <change-id> --strict`
7. Implement only after the proposal and tasks are accepted.
8. During implementation, follow `tasks.md` exactly and mark tasks complete as work is finished.
9. After implementation and validation, archive the change using OpenSpec archive flow.

## Important rule

Do not create a new OpenSpec change if the user asks to revise an existing proposal. Update the existing change folder instead.