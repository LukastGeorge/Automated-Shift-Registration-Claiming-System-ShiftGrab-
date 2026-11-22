import { loadConfig } from './core/config';

describe('loadConfig', () => {
  it('reads the runtime configuration without caching it', async () => {
    const fetchFn = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ apiUrl: '', timeZone: 'Asia/Ho_Chi_Minh' }), { status: 200 }),
    );

    const config = await loadConfig(fetchFn);

    expect(config.apiUrl).toBe('');
    expect(fetchFn).toHaveBeenCalledWith('config.json', { cache: 'no-store' });
  });

  it('fails loudly when the file is missing', async () => {
    const fetchFn = vi.fn().mockResolvedValue(new Response('', { status: 404 }));
    await expect(loadConfig(fetchFn)).rejects.toThrow('HTTP 404');
  });
});
