/**
 * Address advertisement: which local addresses may reach a phone.
 *
 * The gateway used to advertise every non-internal interface address, split by
 * `classifyAddress`. That put a proxy TUN adapter's fake-ip gateway
 * (`198.18.0.1`, RFC 2544 benchmarking space used by Clash/Mihomo) into the
 * public bucket, where it outranked the real global IPv6 address: the panel then
 * showed `https://198.18.0.1:7789`, and a phone running the same kind of proxy
 * dialled its own tunnel and failed the TLS handshake. These tests pin the
 * filtering and the ranking that replaced it.
 *
 * @module dsh-gateway/test/addresses.test
 */

import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { enumerateAddresses, formatHost, preferStable } from '../lib/panel.js'

/** Build one `os.networkInterfaces()` entry list. */
function entries(pairs) {
  return pairs.map(([address, family]) => ({
    address,
    family,
    internal: false,
    mac: '00:00:00:00:00:00',
    netmask: '',
    cidr: '',
  }))
}

/** A realistic Windows adapter table with a proxy TUN and a Teredo tunnel. */
const TABLE = {
  WLAN: entries([
    ['192.168.1.104', 'IPv4'],
    ['2409:8a62:5053:2e20:9060:3bf6:6ea0:b4a5', 'IPv6'],
    ['2409:8a62:5053:2e20:9957:30be:9a92:80cc', 'IPv6'],
    ['fe80::5e70:5d27:f3ca:55a3', 'IPv6'],
  ]),
  Mihomo: entries([['198.18.0.1', 'IPv4']]),
  'Teredo Tunneling Pseudo-Interface': entries([
    ['2001:0:14c9:d502:34d3:b06c:8fec:ec08', 'IPv6'],
    ['fe80::34d3:b06c:8fec:ec08', 'IPv6'],
  ]),
  'VMware Network Adapter VMnet8': entries([['192.168.2.1', 'IPv4']]),
  Tailscale: entries([
    ['100.111.72.120', 'IPv4'],
    ['fd7a:115c:a1e0::172c:4879', 'IPv6'],
  ]),
  'vEthernet (WSL)': entries([['172.29.80.1', 'IPv4']]),
}

describe('advertised addresses', () => {
  it('never advertises a proxy TUN / benchmark address as public', () => {
    const { public: publicList, lan } = enumerateAddresses(TABLE)
    assert.equal(publicList.includes('198.18.0.1'), false, '198.18.0.0/15 is fake-ip space, not a gateway')
    assert.equal(lan.includes('198.18.0.1'), false, 'and it is not a LAN address either')
    // It is the only address on that adapter, so the adapter disappears entirely.
    assert.equal(publicList.some((address) => address.startsWith('198.18.')), false)
  })

  it('prefers the real global IPv6 address for the public edge', () => {
    const { public: publicList } = enumerateAddresses(TABLE)
    assert.ok(publicList.length > 0)
    assert.ok(publicList[0].includes(':'), `public edge must lead with IPv6, got ${publicList[0]}`)
    assert.equal(publicList[0], '2409:8a62:5053:2e20:9060:3bf6:6ea0:b4a5')
  })

  it('drops tunnels, link-local and documentation addresses', () => {
    const { public: publicList, all } = enumerateAddresses(TABLE)
    assert.equal(all.some((address) => address.startsWith('2001:0:')), false, 'Teredo is not dialable')
    assert.equal(all.some((address) => address.startsWith('fe80:')), false, 'link-local needs a zone id')
    const doc = enumerateAddresses({ eth: entries([['2001:db8::1', 'IPv6'], ['192.0.2.10', 'IPv4']]) })
    assert.deepEqual(doc.public, [])
  })

  it('keeps the LAN bucket ordered Wi-Fi first and complete', () => {
    const { lan } = enumerateAddresses(TABLE)
    assert.equal(lan[0], '192.168.1.104')
    assert.ok(lan.includes('192.168.2.1'))
    assert.ok(lan.includes('172.29.80.1'))
    assert.ok(lan.includes('100.111.72.120'), 'Tailscale CGNAT stays reachable as a LAN candidate')
    assert.ok(lan.includes('fd7a:115c:a1e0::172c:4879'), 'a ULA is a LAN address')
  })

  it('reports an empty public bucket when the only candidates are virtual', () => {
    // A machine behind a proxy TUN and nothing else must degrade to the LAN QR
    // instead of advertising a URL that cannot work.
    const only = enumerateAddresses({ Mihomo: entries([['198.18.0.1', 'IPv4']]) })
    assert.deepEqual(only.public, [])
    assert.deepEqual(only.lan, [])
  })

  it('keeps a previously advertised address in front', () => {
    const fresh = ['2409::2', '2409::1', '203.0.113.9']
    assert.deepEqual(preferStable(fresh, ['2409::1']), ['2409::1', '2409::2', '203.0.113.9'])
    assert.deepEqual(preferStable(fresh, []), fresh)
    assert.deepEqual(preferStable(fresh, ['gone::1']), fresh)
    assert.deepEqual(preferStable(['only::1'], ['gone::1']), ['only::1'])
    assert.deepEqual(preferStable(null, ['x']), [])
  })

  it('brackets IPv6 hosts inside URLs', () => {
    assert.equal(formatHost('2409:8a62::1'), '[2409:8a62::1]')
    assert.equal(formatHost('192.168.1.104'), '192.168.1.104')
  })
})
