import { describe, it, expect, mock } from "bun:test";

// Mock the database to avoid dexie dependency issues
mock.module("../src/lib/db", () => ({
  db: {
    classificationRules: { toArray: () => Promise.resolve([]) },
    maskingTerms: { toArray: () => Promise.resolve([]) },
    canonicalIngredients: { toArray: () => Promise.resolve([]) },
  },
}));

import { 
  getOverallStatusColor, 
  getOverallStatusBgColor, 
  getStatusIcon, 
  getRiskLevelColor 
} from '../src/lib/engine/classifier';

describe('Classifier Helpers', () => {
  describe('getOverallStatusColor', () => {
    it('should return correct color for safe status', () => {
      expect(getOverallStatusColor('safe')).toBe('text-emerald-600');
    });

    it('should return correct color for unsafe status', () => {
      expect(getOverallStatusColor('unsafe')).toBe('text-red-600');
    });

    it('should return correct color for unknown status', () => {
      expect(getOverallStatusColor('unknown')).toBe('text-amber-600');
    });
  });

  describe('getOverallStatusBgColor', () => {
    it('should return correct background color for safe status', () => {
      expect(getOverallStatusBgColor('safe')).toBe('bg-emerald-500');
    });

    it('should return correct background color for unsafe status', () => {
      expect(getOverallStatusBgColor('unsafe')).toBe('bg-red-500');
    });

    it('should return correct background color for unknown status', () => {
      expect(getOverallStatusBgColor('unknown')).toBe('bg-amber-500');
    });
  });

  describe('getStatusIcon', () => {
    it('should return correct icon for safe status', () => {
      expect(getStatusIcon('safe')).toBe('✓');
    });

    it('should return correct icon for unsafe status', () => {
      expect(getStatusIcon('unsafe')).toBe('✗');
    });

    it('should return correct icon for unknown status', () => {
      expect(getStatusIcon('unknown')).toBe('?');
    });
  });

  describe('getRiskLevelColor', () => {
    it('should return correct classes for high risk level', () => {
      expect(getRiskLevelColor('high')).toBe('text-red-600 bg-red-50 border-red-200');
    });

    it('should return correct classes for medium risk level', () => {
      expect(getRiskLevelColor('medium')).toBe('text-amber-600 bg-amber-50 border-amber-200');
    });

    it('should return correct classes for low risk level', () => {
      expect(getRiskLevelColor('low')).toBe('text-blue-600 bg-blue-50 border-blue-200');
    });
  });
});
