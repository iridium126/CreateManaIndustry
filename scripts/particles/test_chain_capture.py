import copy,hashlib,json,tempfile,unittest
from pathlib import Path
from validate_chain_capture import load

class ChainTests(unittest.TestCase):
    def fixture(self,root):
        payloads={'buffer.bin':bytes(16),'program.bin':b'binary','upload.bin':bytes(4)}
        for name,value in payloads.items():(root/name).write_bytes(value)
        return dict(schema=3,kind='gpu-command-chain',complete=True,failure_reason='',byte_order='little-endian',storage_bytes=26,sha256={k:hashlib.sha256(v).hexdigest() for k,v in payloads.items()},resources={'b':dict(type='buffer',file='buffer.bin',bytes=16)},programs={'p':dict(binary='program.bin')},commands=[dict(op='upload',args=[37074,0,0],buffer='b',payload='upload.bin'),dict(op='dispatch',args=[1,1,1],inputs=dict(program='p',ssbo={'0':dict(buffer='b',offset=0,bytes=16)},images={}))])
    def test_fail_closed_journal_mutations(self):
        for mutation in ('valid','legacy','partial','overflow','checksum','range','program','binding','publication','path','missing_payload'):
            with self.subTest(mutation=mutation),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);d=self.fixture(root)
                if mutation=='legacy':d['schema']=2
                if mutation=='partial':d['failure_reason']='aborted'
                if mutation=='overflow':d['storage_bytes']=513<<20
                if mutation=='checksum':(root/'buffer.bin').write_bytes(bytes(15)+b'x')
                if mutation=='range':d['commands'][0]['args'][1]=15
                if mutation=='program':d['commands'][1]['inputs']['program']='unknown'
                if mutation=='binding':d['commands'][1]['inputs']['ssbo']['0']['buffer']='unknown'
                if mutation=='publication':d['require_publication']=True
                if mutation=='path':d['sha256']['../escape']='bad'
                if mutation=='missing_payload':del d['sha256']['upload.bin']
                (root/'manifest.json').write_text(json.dumps(d))
                if mutation=='valid':self.assertTrue(load(root)['complete'])
                else:
                    with self.assertRaises((ValueError,FileNotFoundError)):load(root)
if __name__=='__main__':unittest.main()
