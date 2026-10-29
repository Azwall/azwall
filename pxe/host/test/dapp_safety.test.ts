// The attacks the pre-release audit found against the dApp gateway, replayed against its defences.
import { Fr } from '@aztec/foundation/curves/bn254';
import { jsonStringify } from '@aztec/foundation/json-rpc';
import { AztecAddress } from '@aztec/stdlib/aztec-address';
import { FunctionSelector, FunctionType, type ContractArtifact } from '@aztec/stdlib/abi';
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
  const fn = (name: string) => ({ name, functionType: FunctionType.PUBLIC, parameters: [] });
  const artifact = { name: 'Token', functions: [], nonDispatchPublicFunctions: [fn('transfer_in_public'), fn('set_admin')] } as unknown as ContractArtifact;
  const known = async (a: string) => (a === token ? artifact : undefined);
  const call = async (name: string, to = token) => ({ name, to, selector: (await FunctionSelector.fromNameAndParameters(name, [])).toString(), args: [] });

  it('still lists the calls next to the balance changes it shows', async () => {
    const payload = {
      calls: [await call('transfer_in_public'), { name: 'set_authorized', to: address(0xdd), hideMsgSender: true }],
      authWitnesses: [{}],
    };
    const lines = await otherCallLines(payload, known, new Set([token]));
    expect(lines).toHaveLength(2);
    expect(lines[0]).toContain('set_authorized');
    expect(lines[0]).toContain('hides that you call it');
    expect(lines[1]).toBe('1 authorization(s) attached');
  });

  it('does not hide another call to a token behind that token\'s balance change', async () => {
    const lines = await otherCallLines({ calls: [await call('transfer_in_public'), await call('set_admin')] }, known, new Set([token]));
    expect(lines).toHaveLength(1);
    expect(lines[0]).toContain('set_admin');
  });

  it('shows a transfer to a listed token the wallet cannot verify', async () => {
    const lines = await otherCallLines({ calls: [{ name: 'transfer_in_public', to: token }] }, async () => undefined, new Set([token]));
    expect(lines[0]).toContain('unverified');
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

  it('are for the sender of the send under way, even while another account\'s send waits', async () => {
    const guard = new SenderGuard();
    let finishOther!: () => void;
    const held = new Promise<void>(resolve => { finishOther = resolve; });
    const sendingOther = guard.sending(other, () => held);
    let ran = false;
    const sendingMe = guard.sending(me, async () => {
      ran = true;
      expect(() => guard.check(other)).toThrow('another account');
    });
    await Promise.resolve();
    expect(ran).toBe(false); // waits for the other account's send to end
    expect(() => guard.check(me)).toThrow('another account');
    finishOther();
    await Promise.all([sendingOther, sendingMe]);
    expect(ran).toBe(true);
  });

  // Open-test audit (OT-02): each queued send carries its own label, so its hash lands on its own history row.
  it('label each send with the dApp transaction it is, only while it runs', async () => {
    const guard = new SenderGuard();
    let finishFirst!: () => void;
    const held = new Promise<void>(resolve => { finishFirst = resolve; });
    const seen: (string | undefined)[] = [];
    const first = guard.sending(me, async () => { seen.push(guard.label); await held; }, 'first');
    const second = guard.sending(me, async () => { seen.push(guard.label); }, 'second');
    await Promise.resolve();
    expect(guard.label).toBe('first');
    finishFirst();
    await Promise.all([first, second]);
    expect(seen).toEqual(['first', 'second']);
    expect(guard.label).toBeUndefined();
  });

  it('go on after a send that failed', async () => {
    const guard = new SenderGuard();
    await expect(guard.sending(other, async () => { throw new Error('rejected'); })).rejects.toThrow('rejected');
    await expect(guard.sending(me, async () => guard.current)).resolves.toBe(me);
  });
});

describe('a transfer recipient', () => {
  it('must be an address funds can be spent from', async () => {
    const { checkRecipient } = await import('../src/token');
    const tokenAddress = AztecAddress.fromBigIntUnsafe(0xcc0n);
    const valid = await AztecAddress.random();
    await expect(checkRecipient(valid, tokenAddress)).resolves.toBeUndefined();
    await expect(checkRecipient(AztecAddress.ZERO, tokenAddress)).rejects.toThrow('zero address');
    await expect(checkRecipient(tokenAddress, tokenAddress)).rejects.toThrow('token contract itself');
    // Off the curve: find one among small values.
    let off = 1n;
    while (await AztecAddress.fromBigIntUnsafe(off).isValid()) off++;
    await expect(checkRecipient(AztecAddress.fromBigIntUnsafe(off), tokenAddress)).rejects.toThrow('not a valid Aztec address');
  });
});

describe('a real transaction request', () => {
  it('keeps its meaning through the canonical form, which is stable', async () => {
    const { FunctionCall, FunctionSelector, FunctionType } = await import('@aztec/stdlib/abi');
    const { AuthWitness } = await import('@aztec/stdlib/auth-witness');
    const call = FunctionCall.from({
      name: 'swap_exact_tokens_for_tokens',
      to: AztecAddress.fromBigIntUnsafe(0xddn),
      selector: FunctionSelector.fromField(new Fr(0x12345678n)),
      type: FunctionType.PRIVATE,
      hideMsgSender: false,
      isStatic: false,
      args: [new Fr(1n), new Fr(2n)],
      returnType: undefined,
    });
    const payload = new ExecutionPayload([call], [new AuthWitness(new Fr(9n), [new Fr(1n)])], [], []);
    const sent = [json(payload), { from: me, additionalScopes: [token], wait: 'NO_WAIT' }];
    const once = await canonicalArgs('sendTx', sent);
    const twice = await canonicalArgs('sendTx', once);
    expect(twice).toEqual(once);
    const calls = (once[0] as { calls: { name: string; to: string; args: string[] }[] }).calls;
    expect(calls[0]).toMatchObject({ name: 'swap_exact_tokens_for_tokens', to: address(0xdd) });
    expect(calls[0].args).toHaveLength(2);
    expect((once[1] as { additionalScopes: string[] }).additionalScopes).toEqual([token]);
  });
});
