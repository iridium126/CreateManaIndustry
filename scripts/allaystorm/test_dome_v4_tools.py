"""Independent low-degree oracles for the certificate's basis conversion."""
import unittest
import numpy as np
from dome_v4_certificate import bernstein_matrix,scalar_bound
from dome_resource_v4 import coefficients,evaluate,clock_distance
class CertificateMathTest(unittest.TestCase):
 def test_linear_endpoints_cannot_disappear_in_basis_conversion(self):
  np.testing.assert_allclose(np.array([1.,-1.])@bernstein_matrix(1),[1.,0.])
  with self.assertRaises(ValueError):scalar_bound(np.array([[1.,-1.]]),.5,'linear')
  self.assertGreaterEqual(scalar_bound(np.array([[1.,-1.]]),1.00001,'linear'),1.)
 def test_interior_peak_is_bounded_not_just_endpoints(self):
  # 4t(1-t) vanishes at both endpoints and peaks at one halfway through.
  with self.assertRaises(ValueError):scalar_bound(np.array([[0.,4.,-4.]]),.99,'interior')
  self.assertLessEqual(scalar_bound(np.array([[0.,4.,-4.]]),1.00001,'interior'),1.00001)
 def test_quintic_endpoint_position_velocity_acceleration(self):
  rng=np.random.default_rng(14);p,q,v,w,a,b=rng.normal(size=(6,25,3));c=coefficients(p,q,v,w,a,b)
  for order,left,right in [(0,p,q),(1,v,w),(2,a,b)]:
   np.testing.assert_allclose(evaluate(c,0,order),left,atol=1e-12);np.testing.assert_allclose(evaluate(c,1,order),right,atol=1e-12)
 def test_clock_ramp_has_no_distance_or_speed_jump(self):
  for speed in [4.,5.,6.]:
   eps=1e-5
   self.assertAlmostEqual((clock_distance(.5+eps,speed)-clock_distance(.5-eps,speed))/(2*eps),6.,places=7)
   self.assertAlmostEqual(clock_distance(1,speed)-clock_distance(.5,speed),3.,places=12)
 def test_authoring_codec_is_bit_exact(self):
  from generate_dome_v4 import decode_controls
  rng=np.random.default_rng(14);original=rng.normal(size=(17,23,3)).astype('<f4');words=original.view('<u4');delta=words.copy();delta[1:]^=words[:-1]
  planes=delta.view('u1').reshape(*delta.shape,4).transpose(3,2,0,1).copy()
  np.testing.assert_array_equal(decode_controls(planes).view('<u4'),words)
if __name__=='__main__':unittest.main()
