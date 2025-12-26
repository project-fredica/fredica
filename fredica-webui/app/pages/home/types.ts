
import React from 'react';

export interface Space {
  id: string;
  title: string;
  count: number;
}

export interface QuickAction {
  id: string;
  // Fix: Imported React to resolve the React namespace for ReactNode
  icon: React.ReactNode;
  title: string;
  description: string;
}

export interface NavItem {
  id: string;
  // Fix: Imported React to resolve the React namespace for ReactNode
  icon: React.ReactNode;
  label: string;
  badge?: string;
  active?: boolean;
}
