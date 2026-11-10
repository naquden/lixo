// Firestore rules tests for doc/plans/030-friends-that-work.md §2 / §2a.
// Run via: firebase emulators:exec --project demo-lixo --only firestore "npm --prefix firebase/tests test"

import test, { before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from '@firebase/rules-unit-testing';
import {
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  query,
  where,
  writeBatch,
  Timestamp,
} from 'firebase/firestore';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_ID = 'demo-lixo';

const defaultAppearance = () => ({
  avatarId: 'avatar_cat',
  tintHex: null,
  eyeIndex: 0,
  smileIndex: 0,
  equipped: {},
});

// M1 in temp/verify/030_review.md: a reader doc's `readerCardId` must now look like a real
// Firestore auto-id (20 alphanumeric chars) and actually resolve to an existing, owned card.
// Deterministic 20-char ids for test fixtures.
const id20 = (seed) => (seed + '00000000000000000000').slice(0, 20);

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync(path.join(__dirname, '..', 'firestore.rules'), 'utf8'),
    },
  });
});

after(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

function nowMs() {
  return Date.now();
}

async function seedCard(cardId, ownerUid, overrides = {}) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'friend_cards', cardId), {
      ownerUid,
      nick: [1, 2, 3],
      appearance: defaultAppearance(),
      ...overrides,
    });
  });
}

async function seedInvite(code, { cardId, ownerUid, expiresAtMs, uses = 0 }) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'friend_invites', code), {
      cardId,
      ownerUid,
      expiresAt: Timestamp.fromMillis(expiresAtMs),
      uses,
    });
  });
}

async function seedReader(cardId, readerUid, data) {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(doc(context.firestore(), 'friend_cards', cardId, 'readers', readerUid), data);
  });
}

async function adminGetInvite(code) {
  let data;
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const snap = await getDoc(doc(context.firestore(), 'friend_invites', code));
    data = snap.data();
  });
  return data;
}

test('a stranger cannot get a card, and list is denied', async () => {
  await seedCard('cardA', 'ownerA');
  const stranger = testEnv.authenticatedContext('stranger');

  await assertFails(getDoc(doc(stranger.firestore(), 'friend_cards', 'cardA')));
  await assertFails(getDocs(collection(stranger.firestore(), 'friend_cards')));
});

test('the owner can read and write their card, and a foreign ownerUid is denied', async () => {
  await seedCard('cardA', 'ownerA');
  const ownerA = testEnv.authenticatedContext('ownerA');

  await assertSucceeds(getDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA')));
  await assertSucceeds(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA'), {
      ownerUid: 'ownerA',
      nick: [4, 5, 6],
      appearance: defaultAppearance(),
    }),
  );

  // Creating a brand-new card with someone else's uid as owner is denied.
  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardForeign'), {
      ownerUid: 'someoneElse',
      nick: [1, 2, 3],
      appearance: defaultAppearance(),
    }),
  );
});

test('totalStars/updatedAt/string-nickname/free-text appearance fields are denied', async () => {
  const ownerA = testEnv.authenticatedContext('ownerA');

  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA'), {
      ownerUid: 'ownerA',
      nick: [1, 2, 3],
      appearance: defaultAppearance(),
      totalStars: 10,
    }),
  );

  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA'), {
      ownerUid: 'ownerA',
      nick: [1, 2, 3],
      appearance: defaultAppearance(),
      updatedAt: Timestamp.now(),
    }),
  );

  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA'), {
      ownerUid: 'ownerA',
      nick: 'ModigRav42',
      appearance: defaultAppearance(),
    }),
  );

  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA'), {
      ownerUid: 'ownerA',
      nick: [1, 2, 3],
      appearance: { ...defaultAppearance(), freeText: 'hello world' },
    }),
  );
});

test('redeeming a valid invite works in one batch; an expired invite is denied', async () => {
  await seedCard('cardA', 'ownerA');
  const cardB = id20('cardB');
  await seedCard(cardB, 'userB');
  const code = 'ABCD1234';
  await seedInvite(code, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 5 * 60_000 });

  const userB = testEnv.authenticatedContext('userB');
  const batch = writeBatch(userB.firestore());
  batch.update(doc(userB.firestore(), 'friend_invites', code), { uses: 1 });
  batch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: cardB,
    viaInvite: code,
  });
  // M1 in temp/verify/030_review.md: the reciprocal reader doc on the redeemer's OWN card,
  // granting the invite owner access, must land in the SAME batch -- this is what
  // `FirebaseSyncService.redeemInvite`'s transaction actually writes (H3).
  batch.set(doc(userB.firestore(), 'friend_cards', cardB, 'readers', 'ownerA'), {
    readerCardId: 'cardA',
  });
  await assertSucceeds(batch.commit());

  // Expired invite.
  const expiredCode = 'EXPIRED1';
  await seedInvite(expiredCode, {
    cardId: 'cardA',
    ownerUid: 'ownerA',
    expiresAtMs: nowMs() - 1000,
  });
  const userC = testEnv.authenticatedContext('userC');
  const expiredBatch = writeBatch(userC.firestore());
  expiredBatch.update(doc(userC.firestore(), 'friend_invites', expiredCode), { uses: 1 });
  expiredBatch.set(doc(userC.firestore(), 'friend_cards', 'cardA', 'readers', 'userC'), {
    readerCardId: 'cardC',
    viaInvite: expiredCode,
  });
  await assertFails(expiredBatch.commit());
});

test('three different users redeem the same code: all succeed, uses == 3', async () => {
  await seedCard('cardA', 'ownerA');
  const code = 'MULTI001';
  await seedInvite(code, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });

  for (const [i, uid] of ['userB', 'userC', 'userD'].entries()) {
    const readerCardId = id20(`card${uid}`);
    await seedCard(readerCardId, uid);
    const ctx = testEnv.authenticatedContext(uid);
    const batch = writeBatch(ctx.firestore());
    batch.update(doc(ctx.firestore(), 'friend_invites', code), { uses: i + 1 });
    batch.set(doc(ctx.firestore(), 'friend_cards', 'cardA', 'readers', uid), {
      readerCardId,
      viaInvite: code,
    });
    batch.set(doc(ctx.firestore(), 'friend_cards', readerCardId, 'readers', 'ownerA'), {
      readerCardId: 'cardA',
    });
    await assertSucceeds(batch.commit());
  }

  const invite = await adminGetInvite(code);
  assert.equal(invite.uses, 3);
});

test('the same user redeeming twice is denied the second time; uses stays the same', async () => {
  await seedCard('cardA', 'ownerA');
  const code = 'ONCEONLY';
  await seedInvite(code, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });

  const cardB = id20('cardB');
  await seedCard(cardB, 'userB');
  const userB = testEnv.authenticatedContext('userB');
  const firstBatch = writeBatch(userB.firestore());
  firstBatch.update(doc(userB.firestore(), 'friend_invites', code), { uses: 1 });
  firstBatch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: cardB,
    viaInvite: code,
  });
  firstBatch.set(doc(userB.firestore(), 'friend_cards', cardB, 'readers', 'ownerA'), {
    readerCardId: 'cardA',
  });
  await assertSucceeds(firstBatch.commit());

  const secondBatch = writeBatch(userB.firestore());
  secondBatch.update(doc(userB.firestore(), 'friend_invites', code), { uses: 2 });
  secondBatch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: cardB,
    viaInvite: code,
  });
  await assertFails(secondBatch.commit());

  const invite = await adminGetInvite(code);
  assert.equal(invite.uses, 1);
});

test('bumping uses without a reader, by +2, or past 30 is denied; a reader create without bumping uses is denied', async () => {
  await seedCard('cardA', 'ownerA');
  const userB = testEnv.authenticatedContext('userB');

  // uses bump alone, no reader doc created.
  const code1 = 'NOREADR1';
  await seedInvite(code1, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });
  await assertFails(updateDoc(doc(userB.firestore(), 'friend_invites', code1), { uses: 1 }));

  // bump by +2 together with a reader create.
  const code2 = 'PLUSTWO1';
  await seedInvite(code2, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });
  const plusTwoBatch = writeBatch(userB.firestore());
  plusTwoBatch.update(doc(userB.firestore(), 'friend_invites', code2), { uses: 2 });
  plusTwoBatch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: 'cardB',
    viaInvite: code2,
  });
  await assertFails(plusTwoBatch.commit());

  // past 30.
  const code3 = 'OVERTHIR';
  await seedInvite(code3, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000, uses: 30 });
  const overBatch = writeBatch(userB.firestore());
  overBatch.update(doc(userB.firestore(), 'friend_invites', code3), { uses: 31 });
  overBatch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: 'cardB',
    viaInvite: code3,
  });
  await assertFails(overBatch.commit());

  // reader create without bumping uses.
  const code4 = 'NOBUMP01';
  await seedInvite(code4, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });
  await assertFails(
    setDoc(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
      readerCardId: 'cardB',
      viaInvite: code4,
    }),
  );
});

test('invite expiresAt more than 10 min ahead, or uses != 0 at create is denied; creating an invite for someone else\'s card is denied', async () => {
  await seedCard('cardA', 'ownerA');
  await seedCard('cardB', 'ownerB');
  const ownerA = testEnv.authenticatedContext('ownerA');

  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_invites', 'TOOLONG1'), {
      cardId: 'cardA',
      ownerUid: 'ownerA',
      expiresAt: Timestamp.fromMillis(nowMs() + 11 * 60_000),
      uses: 0,
    }),
  );

  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_invites', 'USESNOT0'), {
      cardId: 'cardA',
      ownerUid: 'ownerA',
      expiresAt: Timestamp.fromMillis(nowMs() + 5 * 60_000),
      uses: 1,
    }),
  );

  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_invites', 'NOTMINE1'), {
      cardId: 'cardB',
      ownerUid: 'ownerA',
      expiresAt: Timestamp.fromMillis(nowMs() + 5 * 60_000),
      uses: 0,
    }),
  );

  // Sanity: a valid invite for the caller's own card succeeds.
  await assertSucceeds(
    setDoc(doc(ownerA.firestore(), 'friend_invites', 'VALIDONE'), {
      cardId: 'cardA',
      ownerUid: 'ownerA',
      expiresAt: Timestamp.fromMillis(nowMs() + 5 * 60_000),
      uses: 0,
    }),
  );
});

test('a reader doc without an invite, or with another card\'s invite, is denied', async () => {
  await seedCard('cardA', 'ownerA');
  await seedCard('cardC', 'ownerC');
  const userB = testEnv.authenticatedContext('userB');

  // No viaInvite field, and userB is not the owner of cardA.
  await assertFails(
    setDoc(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
      readerCardId: 'cardB',
    }),
  );

  // viaInvite references an invite for a different card.
  const code = 'OTHERCRD';
  await seedInvite(code, { cardId: 'cardC', ownerUid: 'ownerC', expiresAtMs: nowMs() + 5 * 60_000 });
  await assertFails(
    setDoc(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
      readerCardId: 'cardB',
      viaInvite: code,
    }),
  );
});

test('a card with 10+ equipped entries is denied', async () => {
  const ownerA = testEnv.authenticatedContext('ownerA');

  await assertFails(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA'), {
      ownerUid: 'ownerA',
      nick: [1, 2, 3],
      appearance: {
        ...defaultAppearance(),
        equipped: {
          hat: 'item_hat',
          shoulders_back: 'item_cape',
          arms_gloves: 'item_gloves',
          armor_shirt: 'item_shirt',
          pants: 'item_pants',
          shoes: 'item_shoes',
          hand_right: 'item_sword',
          hand_left: 'item_shield',
          tail: 'item_tail',
          extraSlot: 'item_extra',
        },
      },
    }),
  );

  // Sanity: exactly 9 valid slots is allowed.
  await assertSucceeds(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA'), {
      ownerUid: 'ownerA',
      nick: [1, 2, 3],
      appearance: {
        ...defaultAppearance(),
        equipped: {
          hat: 'item_hat',
          shoulders_back: 'item_cape',
          arms_gloves: 'item_gloves',
          armor_shirt: 'item_shirt',
          pants: 'item_pants',
          shoes: 'item_shoes',
          hand_right: 'item_sword',
          hand_left: 'item_shield',
          tail: 'item_tail',
        },
      },
    }),
  );
});

test('after the reader doc is deleted, get is denied', async () => {
  await seedCard('cardA', 'ownerA');
  await seedReader('cardA', 'userB', { readerCardId: 'cardB' });
  const userB = testEnv.authenticatedContext('userB');

  await assertSucceeds(getDoc(doc(userB.firestore(), 'friend_cards', 'cardA')));

  await testEnv.withSecurityRulesDisabled(async (context) => {
    await deleteDoc(doc(context.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'));
  });

  await assertFails(getDoc(doc(userB.firestore(), 'friend_cards', 'cardA')));
});

// --- M1 in temp/verify/030_review.md: a reader-only redeem (no reciprocal grant), and a
// malformed/non-existent readerCardId, must both be denied -- the vulnerability was that the
// OLD rule let a modified client read a card while staying invisible to that card's owner. ---

test('M1: a redeem batch WITHOUT the reciprocal reader doc on the redeemer\'s own card is denied', async () => {
  await seedCard('cardA', 'ownerA');
  const cardB = id20('cardB');
  await seedCard(cardB, 'userB');
  const code = 'NORECIP1';
  await seedInvite(code, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });

  const userB = testEnv.authenticatedContext('userB');
  const batch = writeBatch(userB.firestore());
  batch.update(doc(userB.firestore(), 'friend_invites', code), { uses: 1 });
  batch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: cardB,
    viaInvite: code,
  });
  // No reciprocal `friend_cards/{cardB}/readers/{ownerA}` write -- ownerA stays unable to see
  // that userB redeemed, while userB would otherwise gain read access to cardA. Must be denied.
  await assertFails(batch.commit());

  const invite = await adminGetInvite(code);
  assert.equal(invite.uses, 0);
});

test('M1: a malformed or non-existent readerCardId is denied even with a reciprocal write attempted', async () => {
  await seedCard('cardA', 'ownerA');
  const userB = testEnv.authenticatedContext('userB');

  // Malformed (contains '/'): would otherwise crash a naive `document(readerCardId)` call.
  const codeSlash = 'SLASHYBD';
  await seedInvite(codeSlash, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });
  const slashBatch = writeBatch(userB.firestore());
  slashBatch.update(doc(userB.firestore(), 'friend_invites', codeSlash), { uses: 1 });
  slashBatch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: 'evil/path',
    viaInvite: codeSlash,
  });
  await assertFails(slashBatch.commit());

  // Well-formed (20 alphanumeric chars) but no such card exists / isn't owned by userB.
  const codeGhost = 'GHOSTCRD';
  await seedInvite(codeGhost, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });
  const ghostCardId = id20('ghostcard');
  const ghostBatch = writeBatch(userB.firestore());
  ghostBatch.update(doc(userB.firestore(), 'friend_invites', codeGhost), { uses: 1 });
  ghostBatch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: ghostCardId,
    viaInvite: codeGhost,
  });
  ghostBatch.set(doc(userB.firestore(), 'friend_cards', ghostCardId, 'readers', 'ownerA'), {
    readerCardId: 'cardA',
  });
  await assertFails(ghostBatch.commit());
});

// --- H2 in temp/verify/030_review.md: a card shaped exactly like the real Kotlin DTOs produce
// (lowercase `EquipmentSlot.id` keys in `equipped`, and a reciprocal reader doc with NO
// `viaInvite` field at all -- not even `viaInvite: null`) must be accepted. ---

test('H2/H3: a card/reader pair shaped like the real Kotlin serializer output succeeds', async () => {
  const ownerA = testEnv.authenticatedContext('ownerA');
  await assertSucceeds(
    setDoc(doc(ownerA.firestore(), 'friend_cards', 'cardA'), {
      ownerUid: 'ownerA',
      nick: [12, 34, 5],
      appearance: {
        avatarId: 'avatar_cat',
        tintHex: null,
        eyeIndex: 0,
        smileIndex: 0,
        // Lowercase EquipmentSlot.id keys (H2), not the Kotlin enum entry names ("HAT", ...).
        equipped: { hat: 'item_hat', tail: 'item_tail' },
      },
    }),
  );

  const cardB = id20('cardB');
  await seedCard(cardB, 'userB');
  const code = 'DTOSHAPE';
  await seedInvite(code, { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 10 * 60_000 });
  const userB = testEnv.authenticatedContext('userB');
  const batch = writeBatch(userB.firestore());
  batch.update(doc(userB.firestore(), 'friend_invites', code), { uses: 1 });
  batch.set(doc(userB.firestore(), 'friend_cards', 'cardA', 'readers', 'userB'), {
    readerCardId: cardB,
    viaInvite: code,
  });
  // The reciprocal doc has ONLY `readerCardId` -- no `viaInvite` key present at all (H3).
  batch.set(doc(userB.firestore(), 'friend_cards', cardB, 'readers', 'ownerA'), {
    readerCardId: 'cardA',
  });
  await assertSucceeds(batch.commit());
});

// --- M4 in temp/verify/030_review.md: GDPR account deletion lists a uid's own invites (across
// all of that uid's cards) to delete them; this must stay scoped to the caller's own uid. ---

test('M4: a card owner can list their own invites; a stranger cannot list them', async () => {
  await seedCard('cardA', 'ownerA');
  await seedInvite('OWNLIST1', { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 5 * 60_000 });
  await seedInvite('OWNLIST2', { cardId: 'cardA', ownerUid: 'ownerA', expiresAtMs: nowMs() + 5 * 60_000 });

  const ownerA = testEnv.authenticatedContext('ownerA');
  const ownQuery = query(collection(ownerA.firestore(), 'friend_invites'), where('ownerUid', '==', 'ownerA'));
  const ownSnap = await assertSucceeds(getDocs(ownQuery));
  assert.equal(ownSnap.size, 2);

  const stranger = testEnv.authenticatedContext('stranger');
  const strangerQuery = query(collection(stranger.firestore(), 'friend_invites'), where('ownerUid', '==', 'ownerA'));
  await assertFails(getDocs(strangerQuery));
});
