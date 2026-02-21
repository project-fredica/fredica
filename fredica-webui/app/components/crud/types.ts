
export interface DataItem {
  id: string;
  name: string;
  email: string;
  role: 'Admin' | 'Editor' | 'Viewer' | 'Manager';
  status: 'Active' | 'Inactive' | 'Pending' | 'Suspended';
  lastLogin: string;
  location: string;
  department: string;
  phone: string;
  createdAt: string;
  progress: number;
}

export type SortOrder = 'asc' | 'desc';

export interface Column {
  key: keyof DataItem | 'actions';
  label: string;
  visible: boolean;
  sortable?: boolean;
}

export interface TableState {
  search: string;
  sortBy: keyof DataItem;
  sortOrder: SortOrder;
  filterRole: string;
  filterStatus: string;
  visibleColumns: (keyof DataItem | 'actions')[];
}
