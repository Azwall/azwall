// The attacks the pre-release audit found against the dApp gateway, replayed against its defences.
import { Fr } from '@aztec/foundation/curves/bn254';
import { jsonStringify } from '@aztec/foundation/json-rpc';
import { AztecAddress } from '@aztec/stdlib/aztec-address';
import { ExecutionPayload } from '@aztec/stdlib/tx';
import { describe, expect, it } from 'vitest';

import { describeAuthWit, otherCallLines } from '../src/dapp/calls';
import { confine, NotGranted } from '../src/dapp/policy';
import { canonicalArgs } from '../src/dapp/raw_call';
import { SenderGuard } from '../src/senders';

const address = (n: number) => AztecAddress.fromBigIntUnsafe(BigInt(n)).toString();
const me = address(0xaa);
const other = address(0xbb);
const token = address(0xcc);
const json = (v: unknown) => JSON.parse(jsonStringify(v));

describe('a site request, in the form the wallet runs it', () => {
  it('drops the readable call an authorization smuggles next to the hash that is really signed', async () => {
    const intent = { consumer: token, innerHash: new Fr(42n).toString(), caller: other, call: { name: 'harmless' } };
    const [from, parsed] = await canonicalArgs('createAuthWit', [me, intent]);
    expect(from).toBe(me);
    expect(parsed).toEqual({ consumer: token, innerHash: new Fr(42n).toString() });
    // So the review says what is signed: an opaque action, not "harmless()".
    const lines = await describeAuthWit(parsed, async () => undefined);
    expect(lines[0]).toContain('an action the wallet cannot read');
  });

  it('writes every address one way, so another account cannot slip into the scopes', async () => {
    const payload = json(ExecutionPayload.empty());
    const bare = other.slice(2); // the same account, without 0x
    const args = await canonicalArgs('simulateTx', [payload, { from: me, additionalScopes: [bare] }]);
    expect(confine('simulateTx', args, me, [other])[1]).toMatchObject({ additionalScopes: [] });
  });

  it('refuses messages sent as another account', () => {
    expect(() => confine('sendTx', [{}, { from: me, sendMessagesAs: other }], me, [other])).toThrow(NotGranted);
    expect(confine('sendTx', [{}, { from: me, sendMessagesAs: me }], me, [other])).toBeTruthy();
  });
});

describe('a transaction summary', () => {
  it('still lists the calls next to the balance changes it shows', async () => {
    const payload = {
      calls: [
        { name: 'transfer', to: token },
        { name: 'set_authorized', to: address(0xdd), hideMsgSender: true },
      ],
      authWitnesses: [{}],
    };
    const lines = await otherCallLines(payload, async () => undefined, new Set([token]));
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('set_authorized');
    expect(lines[0]).toContain('hides that you call it');
    expect(lines[1]).toBe('1 authorization(s) attached');
  });
});

describe('authorizations signed while sending', () => {
  it('are for the sender only, and for nobody in a transaction sent without one', async () => {
    const guard = new SenderGuard();
    expect(() => guard.check(other)).not.toThrow(); // a site's own createAuthWit, already confined to its account
    await guard.sending(me, async () => {
      expect(() => guard.check(me)).not.toThrow();
      expect(() => guard.check(other)).toThrow('another account');
    });
    await guard.sending('NO_FROM', async () => expect(() => guard.check(me)).toThrow('another account'));
    expect(() => guard.check(other)).not.toThrow();
  });
});
