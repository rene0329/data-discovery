import gzip
import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import io
from contextlib import redirect_stderr

script=Path(__file__).resolve().parents[1]/'app'/'train.py'
if not script.exists(): script=Path('/app/train.py')
spec=importlib.util.spec_from_file_location('train',script)
train=importlib.util.module_from_spec(spec)
spec.loader.exec_module(train)


class ReaderTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path=Path(self.tmp.name)/'higgs.gz'

    def write(self,text):
        with gzip.open(self.path,'wt') as f:f.write(text)

    def test_compressed_and_plain(self):
        row='1,'+','.join(['2.5']*28)+'\n'
        self.write(row)
        self.assertEqual(train.read_sample(self.path,5),([[2.5]*28],[1.]))
        self.path.write_text(row)
        self.assertEqual(train.read_sample(self.path,5),([[2.5]*28],[1.]))

    def test_limit_stops_before_invalid_following_row(self):
        self.write('0,'+','.join(['1']*28)+'\ninvalid\n')
        self.assertEqual(len(train.read_sample(self.path,1)[0]),1)
        with self.assertRaisesRegex(ValueError,'row 2'):train.read_sample(self.path,2)

    def test_rejects_bad_rows(self):
        for row in ['', '0,1\n', '2,'+','.join(['1']*28), '0,nan,'+','.join(['1']*27),
                    '0,1e300,'+','.join(['1']*27),'0,text,'+','.join(['1']*27)]:
            with self.subTest(row=row):
                self.write(row)
                with self.assertRaises(ValueError):train.read_sample(self.path,5)

    def test_positive_options_and_explicit_epoch_override(self):
        with patch.dict('os.environ', {'N_EPOCHS':'1'}):
            self.assertEqual(train.arguments(['--epochs','10']).epochs,10)
        self.assertEqual(train.arguments([]).max_samples,500000)
        for field in ['--epochs','--batch-size','--threads','--max-samples']:
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):train.arguments([field,'0'])

    def test_real_training_updates_and_writes_ten_epochs(self):
        try:import torch
        except ImportError:self.skipTest('PyTorch integration runs in the build image')
        rows=[]
        for i in range(128):
            label=i%2
            rows.append(str(label)+','+','.join([str(2*label-1)]*28))
        self.write('\n'.join(rows))
        config=train.arguments(['--data-path',str(self.path),'--output-dir',self.tmp.name,
                                '--epochs','10','--max-samples','128','--batch-size','32'])
        r=train.train(config)
        self.assertTrue(r['parameters_changed'])
        self.assertEqual([x['epoch'] for x in r['history']],list(range(1,11)))
        self.assertLess(r['history'][-1]['loss'],r['history'][0]['loss'])
        self.assertTrue(all(math.isfinite(x['loss']) for x in r['history']))
        self.assertEqual(json.loads((Path(self.tmp.name)/'summary.json').read_text())['samples'],128)
        self.assertNotIn('test_accuracy',(Path(self.tmp.name)/'metrics.log').read_text())


if __name__=='__main__':unittest.main()
